package com.hz.crm.task;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.hz.crm.activity.Activity;
import com.hz.crm.activity.ActivityRepository;
import com.hz.crm.product.Product;
import com.hz.crm.product.ProductRepository;
import com.hz.crm.security.AccessService;
import com.hz.crm.security.AccessService.Scope;
import com.hz.crm.user.AppUser;
import com.hz.crm.user.Role;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Sales tasks: a leader assigns units of a product to someone below them, who may split them further down.
 * Everyone sees tasks assigned to themselves or anyone below them, plus tasks they assigned.
 */
@RestController
@RequestMapping("/api/tasks")
public class SalesTaskController {

	public enum Status { NOT_STARTED, IN_PROGRESS, COMPLETED }

	public record TaskView(Long id, Long parentId, String title, Long productId, String productName, String unit,
			int units, Long assignedToId, String assignedToName, Long assignedById, String assignedByName,
			LocalDateTime createdAt, int splitUnits, int subTaskCount, long soldUnits, Status status) {
	}

	public record CreateTaskRequest(Long parentId, Long productId, @NotNull Long assigneeId,
			@NotNull @Min(1) @Max(1_000_000) Integer units, @Size(max = 200) String title) {
	}

	public record UpdateTaskRequest(@NotNull @Min(1) @Max(1_000_000) Integer units, @Size(max = 200) String title) {
	}

	private final SalesTaskRepository tasks;
	private final ProductRepository products;
	private final ActivityRepository activities;
	private final AccessService access;

	public SalesTaskController(SalesTaskRepository tasks, ProductRepository products, ActivityRepository activities,
			AccessService access) {
		this.tasks = tasks;
		this.products = products;
		this.activities = activities;
		this.access = access;
	}

	// ---------- reading ----------

	@GetMapping
	public List<TaskView> list() {
		Scope s = access.scope();
		Long me = s.me().getId();
		List<SalesTask> all = tasks.findByTenantId(s.tenantId());
		List<SalesTask> visible = all.stream()
				.filter(t -> s.canSeeOwner(t.getAssignedToId()) || me.equals(t.getAssignedById()))
				.sorted(Comparator.comparing(SalesTask::getCreatedAt).reversed())
				.toList();
		return views(s, all, visible);
	}

	private List<TaskView> views(Scope s, List<SalesTask> all, List<SalesTask> selected) {
		Map<Long, AppUser> users = s.tenantUsers().stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));
		Map<Long, Product> productById = products.findByTenantIdOrderByNameAsc(s.tenantId()).stream()
				.collect(Collectors.toMap(Product::getId, Function.identity()));
		Map<Long, List<SalesTask>> children = all.stream().filter(t -> t.getParentId() != null)
				.collect(Collectors.groupingBy(SalesTask::getParentId));
		Map<Long, Long> soldByTask = allocate(s, all,
				activities.findByTenantIdAndProductIdIsNotNull(s.tenantId()));

		return selected.stream().map(t -> {
			long sold = soldByTask.getOrDefault(t.getId(), 0L);
			List<SalesTask> kids = children.getOrDefault(t.getId(), List.of());
			Product p = productById.get(t.getProductId());
			Status status = sold >= t.getUnits() ? Status.COMPLETED : sold > 0 ? Status.IN_PROGRESS : Status.NOT_STARTED;
			return new TaskView(t.getId(), t.getParentId(), t.getTitle(), t.getProductId(),
					p == null ? "?" : p.getName(), p == null ? "units" : p.getUnit(), t.getUnits(),
					t.getAssignedToId(), name(users.get(t.getAssignedToId())), t.getAssignedById(),
					name(users.get(t.getAssignedById())), t.getCreatedAt(),
					kids.stream().mapToInt(SalesTask::getUnits).sum(), kids.size(), sold, status);
		}).toList();
	}

	/** Sales count from the start of the day the task was assigned. */
	private static LocalDateTime countsFrom(SalesTask t) {
		return t.getCreatedAt().toLocalDate().atStartOfDay();
	}

	/**
	 * Units sold per task. Sales by a person and everyone below them count toward that person's tasks for the
	 * product, but each sale is counted only once per person: it fills their oldest unfinished task first and the
	 * rest flows to the next one. A sale only counts toward tasks that existed on the day of the sale. Units sold
	 * beyond all targets are added to the newest task the sale could count toward, so over-achievement stays visible.
	 * Parent and sub-tasks belong to different people, so a leader's task still rolls up the team's sales.
	 */
	private Map<Long, Long> allocate(Scope s, List<SalesTask> all, List<Activity> sales) {
		Map<Long, Long> sold = new HashMap<>();
		Map<String, List<SalesTask>> byPersonAndProduct = all.stream()
				.collect(Collectors.groupingBy(t -> t.getAssignedToId() + ":" + t.getProductId()));
		Comparator<SalesTask> oldestFirst = Comparator.comparing(SalesTask::getCreatedAt).thenComparing(SalesTask::getId);

		for (List<SalesTask> group : byPersonAndProduct.values()) {
			List<SalesTask> ordered = group.stream().sorted(oldestFirst).toList();
			ordered.forEach(t -> sold.put(t.getId(), 0L));
			Long productId = ordered.get(0).getProductId();
			Set<Long> team = access.subtree(ordered.get(0).getAssignedToId(), s.tenantUsers());
			List<Activity> stream = sales.stream()
					.filter(a -> productId.equals(a.getProductId()) && team.contains(a.getOwnerId())
							&& a.getActivityTime() != null && a.getUnitsSold() != null)
					.sorted(Comparator.comparing(Activity::getActivityTime).thenComparing(Activity::getId))
					.toList();

			for (Activity a : stream) {
				long left = a.getUnitsSold();
				SalesTask newestEligible = null;
				for (SalesTask t : ordered) {
					if (a.getActivityTime().isBefore(countsFrom(t))) {
						break; // this and all newer tasks did not exist yet on the day of the sale
					}
					newestEligible = t;
					long room = t.getUnits() - sold.get(t.getId());
					if (room > 0 && left > 0) {
						long take = Math.min(room, left);
						sold.merge(t.getId(), take, Long::sum);
						left -= take;
					}
				}
				if (left > 0 && newestEligible != null) {
					sold.merge(newestEligible.getId(), left, Long::sum);
				}
			}
		}
		return sold;
	}

	private static String name(AppUser u) {
		return u == null ? "Unknown" : u.getFullName() != null ? u.getFullName() : u.getUsername();
	}

	private TaskView view(Scope s, SalesTask t) {
		return views(s, tasks.findByTenantId(s.tenantId()), List.of(t)).get(0);
	}

	// ---------- writing ----------

	private SalesTask find(Long id, Scope s) {
		return tasks.findById(id).filter(t -> s.tenantId().equals(t.getTenantId()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
	}

	private static int unitsOf(List<SalesTask> list, Long excludeId) {
		return list.stream().filter(t -> !t.getId().equals(excludeId)).mapToInt(SalesTask::getUnits).sum();
	}

	private static String title(String title) {
		return title == null || title.isBlank() ? null : title.trim();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public TaskView create(@Valid @RequestBody CreateTaskRequest req) {
		Scope s = access.scope();
		Long me = s.me().getId();
		AppUser assignee = s.tenantUsers().stream().filter(u -> u.getId().equals(req.assigneeId())).findFirst()
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown team member"));
		if (assignee.getId().equals(me) || !s.canSeeOwner(assignee.getId()) || assignee.getRole() != Role.MEMBER) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You can only assign tasks to people below you");
		}
		if (!assignee.isActive()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This team member is inactive");
		}

		SalesTask t = new SalesTask();
		t.setTenantId(s.tenantId());
		t.setAssignedById(me);
		t.setAssignedToId(assignee.getId());
		t.setUnits(req.units());
		t.setTitle(title(req.title()));

		if (req.parentId() != null) {
			SalesTask parent = find(req.parentId(), s);
			if (!me.equals(parent.getAssignedToId())) {
				throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only split tasks assigned to you");
			}
			int left = parent.getUnits() - unitsOf(tasks.findByParentId(parent.getId()), null);
			if (req.units() > left) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"Only " + left + " unit(s) of this task are left to split");
			}
			t.setParentId(parent.getId());
			t.setProductId(parent.getProductId());
			if (t.getTitle() == null) {
				t.setTitle(parent.getTitle());
			}
		} else {
			if (req.productId() == null) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a product");
			}
			Product p = products.findById(req.productId()).filter(x -> s.tenantId().equals(x.getTenantId()))
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown product"));
			if (!p.isActive()) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This product is inactive");
			}
			t.setProductId(p.getId());
		}
		return view(s, tasks.save(t));
	}

	@PutMapping("/{id}")
	public TaskView update(@PathVariable Long id, @Valid @RequestBody UpdateTaskRequest req) {
		Scope s = access.scope();
		SalesTask t = find(id, s);
		if (!s.me().getId().equals(t.getAssignedById())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the person who assigned this task can change it");
		}
		int split = unitsOf(tasks.findByParentId(t.getId()), null);
		if (req.units() < split) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					split + " unit(s) are already split to the team; units cannot be lower than that");
		}
		if (t.getParentId() != null) {
			SalesTask parent = tasks.findById(t.getParentId()).orElseThrow();
			int left = parent.getUnits() - unitsOf(tasks.findByParentId(parent.getId()), t.getId());
			if (req.units() > left) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"Only " + left + " unit(s) of the parent task are available");
			}
		}
		t.setUnits(req.units());
		t.setTitle(title(req.title()));
		return view(s, tasks.save(t));
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		Scope s = access.scope();
		SalesTask t = find(id, s);
		if (!Objects.equals(s.me().getId(), t.getAssignedById())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the person who assigned this task can delete it");
		}
		if (tasks.existsByParentId(t.getId())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"This task has been split to the team. Remove those sub-tasks first.");
		}
		tasks.delete(t);
		return ResponseEntity.noContent().build();
	}
}
