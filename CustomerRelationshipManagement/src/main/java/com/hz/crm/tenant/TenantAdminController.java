package com.hz.crm.tenant;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
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
import com.hz.crm.customer.Customer;
import com.hz.crm.customer.CustomerRepository;
import com.hz.crm.product.Product;
import com.hz.crm.product.ProductRepository;
import com.hz.crm.security.AccessService;
import com.hz.crm.security.AccessService.Scope;
import com.hz.crm.task.SalesTask;
import com.hz.crm.task.SalesTaskRepository;
import com.hz.crm.task.TaskProgressService;
import com.hz.crm.user.AppUser;
import com.hz.crm.user.AppUserRepository;
import com.hz.crm.user.LoginNames;
import com.hz.crm.user.TeamRole;
import com.hz.crm.user.TeamRoleRepository;
import com.hz.crm.user.UserController;
import com.hz.crm.user.Role;
import com.hz.crm.user.UserController.UserView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Super admin: manage tenants, create each tenant's first admin, and view all tenant details (read-only). */
@RestController
@RequestMapping("/api/admin/tenants")
public class TenantAdminController {

	/** One tenant in the list: activity and size only (no money; sales figures are in the tenant's details). */
	public record TenantView(Long id, String name, String code, boolean active, LocalDateTime createdAt,
			long userCount, long adminCount, long customerCount, long activityCount, long activitiesLast7Days,
			long productCount, long taskCount, LocalDateTime lastActivityAt) {
	}

	/** A tenant's sales: activities that recorded a sale, and how far its tasks have got. */
	public record SalesSummary(long deals, long unitsSold, BigDecimal salesAmount, BigDecimal creditAmount,
			long unitsAssigned, long unitsSoldOnTasks, long openTasks, long completedTasks) {
	}

	public record TaskRow(Long id, Long parentId, String title, String productName, String unit, int units,
			long soldUnits, String assignedToName, String assignedByName, LocalDateTime createdAt, String status) {
	}

	public record TenantDetail(TenantView tenant, SalesSummary sales, List<UserView> users, List<Customer> customers,
			List<Activity> activities, List<Product> products, List<TaskRow> tasks) {
	}

	public record CreateTenantRequest(@NotBlank String name, @NotBlank @Size(max = 5) String code,
			@NotBlank String adminUsername, @NotBlank @Size(min = 8, max = 72) String adminPassword, String adminFullName) {
	}

	public record ActiveRequest(boolean active) {
	}

	private record TenantData(List<AppUser> users, List<Customer> customers, List<Activity> activities,
			List<Product> products, List<SalesTask> tasks) {
	}

	private final TenantRepository tenants;
	private final AppUserRepository users;
	private final CustomerRepository customers;
	private final ActivityRepository activities;
	private final ProductRepository products;
	private final SalesTaskRepository tasks;
	private final TaskProgressService progress;
	private final AccessService access;
	private final PasswordEncoder encoder;
	private final TeamRoleRepository teamRoles;

	public TenantAdminController(TenantRepository tenants, AppUserRepository users, CustomerRepository customers,
			ActivityRepository activities, ProductRepository products, SalesTaskRepository tasks,
			TaskProgressService progress, AccessService access, PasswordEncoder encoder, TeamRoleRepository teamRoles) {
		this.teamRoles = teamRoles;
		this.tenants = tenants;
		this.users = users;
		this.customers = customers;
		this.activities = activities;
		this.products = products;
		this.tasks = tasks;
		this.progress = progress;
		this.access = access;
		this.encoder = encoder;
	}

	private TenantData load(Long tenantId) {
		List<AppUser> us = users.findByTenantId(tenantId);
		List<Long> ids = us.stream().map(AppUser::getId).toList();
		List<Product> ps = products.findByTenantIdOrderByNameAsc(tenantId);
		List<SalesTask> ts = tasks.findByTenantId(tenantId);
		if (ids.isEmpty()) {
			return new TenantData(us, List.of(), List.of(), ps, ts);
		}
		return new TenantData(us, customers.findByTenantIdAndOwnerIdIn(tenantId, ids, Sort.by("id").descending()),
				activities.findByTenantIdAndOwnerIdIn(tenantId, ids), ps, ts);
	}

	private TenantView view(Tenant t, TenantData d) {
		LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
		LocalDateTime last = d.activities().stream().map(Activity::getActivityTime).filter(java.util.Objects::nonNull)
				.max(LocalDateTime::compareTo).orElse(null);
		return new TenantView(t.getId(), t.getName(), t.getCode(), t.isActive(), t.getCreatedAt(), d.users().size(),
				d.users().stream().filter(u -> u.getRole() == Role.TENANT_ADMIN).count(), d.customers().size(),
				d.activities().size(),
				d.activities().stream().filter(a -> a.getActivityTime() != null && a.getActivityTime().isAfter(weekAgo)).count(),
				d.products().size(), d.tasks().size(), last);
	}

	private static BigDecimal nz(BigDecimal v) {
		return v == null ? BigDecimal.ZERO : v;
	}

	private Tenant find(Long id) {
		return tenants.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@GetMapping
	public List<TenantView> list() {
		return tenants.findAll(Sort.by("id")).stream().map(t -> view(t, load(t.getId()))).toList();
	}

	/** Everything about one tenant: users and hierarchy, customers, activities, products, tasks and sales figures. */
	@GetMapping("/{id}/details")
	public TenantDetail details(@PathVariable Long id) {
		Tenant t = find(id);
		TenantData d = load(id);
		Map<Long, String> roleNames = teamRoles.findByTenantIdOrderByNameAsc(id).stream()
				.collect(Collectors.toMap(TeamRole::getId, TeamRole::getName));
		List<UserView> uv = d.users().stream().map(u -> UserController.view(u, roleNames)).toList();

		// task progress is worked out the same way the tenant's own users see it
		Scope scope = new Scope(access.current(), id, d.users().stream().map(AppUser::getId).collect(Collectors.toSet()), d.users());
		List<Activity> sales = d.activities().stream().filter(a -> a.getProductId() != null).toList();
		Map<Long, Long> sold = d.tasks().isEmpty() ? Map.of() : progress.soldByTask(scope, d.tasks(), sales);
		Map<Long, String> names = d.users().stream()
				.collect(Collectors.toMap(AppUser::getId, u -> u.getFullName() != null ? u.getFullName() : u.getUsername()));
		Map<Long, Product> productById = d.products().stream().collect(Collectors.toMap(Product::getId, p -> p));
		List<TaskRow> rows = d.tasks().stream()
				.sorted(java.util.Comparator.comparing(SalesTask::getCreatedAt).reversed())
				.map(task -> {
					long s = sold.getOrDefault(task.getId(), 0L);
					Product p = productById.get(task.getProductId());
					return new TaskRow(task.getId(), task.getParentId(), task.getTitle(), p == null ? "?" : p.getName(),
							p == null ? "units" : p.getUnit(), task.getUnits(), s, names.getOrDefault(task.getAssignedToId(), "?"),
							names.getOrDefault(task.getAssignedById(), "?"), task.getCreatedAt(),
							TaskProgressService.status(task, s).name());
				}).toList();
		// top-level tasks only, so units split down the hierarchy are not counted twice
		List<SalesTask> top = d.tasks().stream().filter(x -> x.getParentId() == null).toList();
		long completed = rows.stream().filter(r -> "COMPLETED".equals(r.status())).count();
		SalesSummary summary = new SalesSummary(sales.size(),
				sales.stream().mapToLong(a -> a.getUnitsSold() == null ? 0 : a.getUnitsSold()).sum(),
				sales.stream().map(a -> nz(a.getSaleAmount())).reduce(BigDecimal.ZERO, BigDecimal::add),
				sales.stream().map(a -> nz(a.getCreditAmount())).reduce(BigDecimal.ZERO, BigDecimal::add),
				top.stream().mapToLong(SalesTask::getUnits).sum(),
				top.stream().mapToLong(x -> Math.min(x.getUnits(), sold.getOrDefault(x.getId(), 0L))).sum(),
				rows.size() - completed, completed);
		return new TenantDetail(view(t, d), summary, uv, d.customers(), d.activities(), d.products(), rows);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Transactional
	public TenantView create(@Valid @RequestBody CreateTenantRequest req) {
		String code = LoginNames.normalizeCode(req.code());
		String adminLogin = LoginNames.build(req.adminUsername(), code);
		if (tenants.existsByNameIgnoreCase(req.name().trim())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Tenant name already exists");
		}
		if (tenants.existsByCodeIgnoreCase(code)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Tenant code already in use");
		}
		if (users.existsByUsernameIgnoreCase(adminLogin)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
		}
		Tenant t = new Tenant();
		t.setName(req.name().trim());
		t.setCode(code);
		t = tenants.save(t);

		AppUser admin = new AppUser();
		admin.setUsername(adminLogin);
		admin.setPasswordHash(encoder.encode(req.adminPassword()));
		admin.setFullName(req.adminFullName() == null || req.adminFullName().isBlank() ? adminLogin
				: req.adminFullName().trim());
		admin.setRole(Role.TENANT_ADMIN);
		admin.setTenantId(t.getId());
		users.save(admin);
		return view(t, load(t.getId()));
	}

	@PutMapping("/{id}/active")
	public TenantView setActive(@PathVariable Long id, @RequestBody ActiveRequest req) {
		Tenant t = find(id);
		t.setActive(req.active());
		return view(tenants.save(t), load(id));
	}
}
