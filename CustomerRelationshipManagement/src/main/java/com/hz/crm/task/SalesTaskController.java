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
import com.hz.crm.stock.StockService;
import com.hz.crm.task.TaskProgressService.Status;
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

	public record TaskView(Long id, Long parentId, String title, Long productId, String productName, String unit,
			int units, Long assignedToId, String assignedToName, Long assignedById, String assignedByName,
			LocalDateTime createdAt, int splitUnits, int subTaskCount, long soldUnits, Status status,
			/** units of the whole task still unsold (team included) */ long teamBalance,
			/** units still to be sold by the assignee themselves, after units split to the team */ long ownBalance,
			/** this task's share of the sale amounts and of the credit still owed, by units counted toward it */
			java.math.BigDecimal salesAmount, java.math.BigDecimal creditAmount,
			/** the sales (activities) that count toward this task */ List<Long> dealIds,
			/** of soldUnits, the units the assignee sold themselves (the rest was sold by their team) */ long soldByAssignee,
			/** units of each sale (activity id -> units) that count toward this task */ Map<Long, Long> dealUnits,
			/** the task's units were taken from stock */ boolean fromStock) {
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
	private final TaskProgressService progress;
	private final StockService stock;

	public SalesTaskController(SalesTaskRepository tasks, ProductRepository products, ActivityRepository activities,
			AccessService access, TaskProgressService progress, StockService stock) {
		this.stock = stock;
		this.progress = progress;
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
		List<SalesTask> related = progress.related(all, selected);
		List<Activity> sales = salesFor(s);
		Map<Long, Map<Long, Long>> parts = progress.allocation(s, related, sales);
		Map<Long, Long> soldByTask = new java.util.HashMap<>();
		parts.forEach((id, p) -> soldByTask.put(id, p.values().stream().mapToLong(Long::longValue).sum()));
		Map<Long, Activity> saleById = sales.stream().collect(Collectors.toMap(Activity::getId, Function.identity()));
		Map<Long, Long> own = progress.ownBalance(related, soldByTask);

		return selected.stream().map(t -> {
			long sold = soldByTask.getOrDefault(t.getId(), 0L);
			List<SalesTask> kids = children.getOrDefault(t.getId(), List.of());
			Product p = productById.get(t.getProductId());
			Status status = TaskProgressService.status(t, sold);
			return new TaskView(t.getId(), t.getParentId(), t.getTitle(), t.getProductId(),
					p == null ? "?" : p.getName(), p == null ? "units" : p.getUnit(), t.getUnits(),
					t.getAssignedToId(), name(users.get(t.getAssignedToId())), t.getAssignedById(),
					name(users.get(t.getAssignedById())), t.getCreatedAt(),
					kids.stream().mapToInt(SalesTask::getUnits).sum(), kids.size(), sold, status,
					Math.max(0, t.getUnits() - sold), own.getOrDefault(t.getId(), 0L),
					share(parts.get(t.getId()), saleById, Activity::getSaleAmount),
					share(parts.get(t.getId()), saleById, Activity::getCreditAmount),
					parts.getOrDefault(t.getId(), Map.of()).keySet().stream().toList(),
					parts.getOrDefault(t.getId(), Map.of()).entrySet().stream()
							.filter(e -> saleById.containsKey(e.getKey()) && t.getAssignedToId().equals(saleById.get(e.getKey()).getOwnerId()))
							.mapToLong(Map.Entry::getValue).sum(),
					parts.getOrDefault(t.getId(), Map.of()), t.isFromStock());
		}).toList();
	}

	/** Sum of a money field over the sales counted toward a task, in proportion to the units counted from each sale. */
	private static java.math.BigDecimal share(Map<Long, Long> parts, Map<Long, Activity> saleById,
			Function<Activity, java.math.BigDecimal> field) {
		java.math.BigDecimal total = java.math.BigDecimal.ZERO;
		if (parts == null) {
			return total;
		}
		for (Map.Entry<Long, Long> e : parts.entrySet()) {
			Activity a = saleById.get(e.getKey());
			java.math.BigDecimal value = a == null ? null : field.apply(a);
			if (value == null || a.getUnitsSold() == null || a.getUnitsSold() == 0) {
				continue;
			}
			total = total.add(value.multiply(java.math.BigDecimal.valueOf(e.getValue()))
					.divide(java.math.BigDecimal.valueOf(a.getUnitsSold()), 2, java.math.RoundingMode.HALF_UP));
		}
		return total;
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

	/** Units of each task still with its assignee: not handed to their team and not sold by them yet (task id -> units). */
	private Map<Long, Long> ownBalances(Scope s) {
		Long me = s.me().getId();
		List<SalesTask> all = tasks.findByTenantId(s.tenantId());
		List<SalesTask> related = progress.related(all, all.stream()
				.filter(t -> s.canSeeOwner(t.getAssignedToId()) || me.equals(t.getAssignedById())).toList());
		return progress.ownBalance(related, progress.soldByTask(s, related, salesFor(s)));
	}

	/**
	 * Sales that can count toward the tasks this user sees: the admin needs all of them; a member's tasks only
	 * count sales by themselves and people below them.
	 */
	private List<Activity> salesFor(Scope s) {
		return s.me().getRole() == Role.TENANT_ADMIN ? activities.findByTenantIdAndProductIdIsNotNull(s.tenantId())
				: activities.findSales(s.tenantId(), s.ownerIds());
	}

	private void checkStock(Scope s, Product p, int extraUnits) {
		long left = stock.level(s.tenantId(), p.getId()).left();
		if (extraUnits > left) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, left <= 0
					? "No " + p.getName() + " stock left to assign. Add stock in the Stock section first."
					: "Only " + left + " " + p.getUnit() + " of " + p.getName() + " are left in stock");
		}
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
			long left = ownBalances(s).getOrDefault(parent.getId(), 0L);
			if (req.units() > left) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, left == 0
						? "You have no units left on this task to hand out"
						: "You have only " + left + " unit(s) left on this task (not sold or handed out yet)");
			}
			t.setParentId(parent.getId());
			t.setProductId(parent.getProductId());
			if (t.getTitle() == null) {
				t.setTitle(parent.getTitle());
			}
		} else {
			if (s.me().getRole() != Role.TENANT_ADMIN) {
				// members hand out units from their own balance, so the task must come out of one of their tasks
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose which of your tasks the units come from");
			}
			if (t.getTitle() == null) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a title for the task");
			}
			if (req.productId() == null) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a product");
			}
			Product p = products.findById(req.productId()).filter(x -> s.tenantId().equals(x.getTenantId()))
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown product"));
			if (!p.isActive()) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This product is inactive");
			}
			checkStock(s, p, req.units());
			t.setFromStock(true);
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
		Map<Long, Long> own = ownBalances(s);
		// units already handed to the team or sold by the assignee cannot be taken back
		long used = t.getUnits() - own.getOrDefault(t.getId(), 0L);
		if (req.units() < used) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, used
					+ " unit(s) are already handed to the team or sold; units cannot be lower than that");
		}
		int extra = req.units() - t.getUnits();
		if (extra > 0) {
			if (t.getParentId() != null) {
				long left = own.getOrDefault(t.getParentId(), 0L);
				if (extra > left) {
					throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You can add at most " + left
							+ " more unit(s); that is all you have left on your task");
				}
			} else if (t.isFromStock()) {
				Product p = products.findById(t.getProductId()).orElseThrow();
				checkStock(s, p, extra);
			}
		}
		t.setUnits(req.units());
		String newTitle = title(req.title());
		if (newTitle == null) {
			if (t.getParentId() == null) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a title for the task");
			}
			newTitle = tasks.findById(t.getParentId()).map(SalesTask::getTitle).orElse(null); // sub-task: same as its task
		}
		t.setTitle(newTitle);
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
