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

	/** customers and activities are the latest 200; the tenant view has the totals */
	public record TenantDetail(TenantView tenant, SalesSummary sales, List<UserView> users, List<Customer> customers,
			List<com.hz.crm.activity.ActivitySearch.Row> activities, List<Product> products, List<TaskRow> tasks) {
	}

	public record CreateTenantRequest(@NotBlank String name, @NotBlank @Size(max = 5) String code,
			@NotBlank String adminUsername, @NotBlank @Size(min = 8, max = 72) String adminPassword, String adminFullName,
			@Size(max = 254) String adminEmail, @Size(max = 25) String adminMobile) {
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
	private final com.hz.crm.activity.ActivitySearch activitySearch;
	private final com.hz.crm.user.LoginIdentifiers identifiers;

	public TenantAdminController(TenantRepository tenants, AppUserRepository users, CustomerRepository customers,
			ActivityRepository activities, ProductRepository products, SalesTaskRepository tasks,
			TaskProgressService progress, AccessService access, PasswordEncoder encoder, TeamRoleRepository teamRoles,
			com.hz.crm.activity.ActivitySearch activitySearch, com.hz.crm.user.LoginIdentifiers identifiers) {
		this.identifiers = identifiers;
		this.activitySearch = activitySearch;
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

	/** How many of each thing every tenant has, counted in the database (no rows are loaded). */
	private record Counts(Map<Long, Long> customers, Map<Long, Object[]> activities, Map<Long, Long> products,
			Map<Long, Long> tasks, Map<Long, List<AppUser>> users) {
		long of(Map<Long, Long> m, Long id) { return m.getOrDefault(id, 0L); }
	}

	private static Map<Long, Long> byTenant(List<Object[]> rows) {
		return rows.stream().collect(Collectors.toMap(r -> (Long) r[0], r -> ((Number) r[1]).longValue()));
	}

	private Counts counts() {
		return new Counts(byTenant(customers.countByTenant()),
				activities.tenantStats(LocalDateTime.now().minusDays(7)).stream().collect(Collectors.toMap(r -> (Long) r[0], r -> r)),
				byTenant(products.countByTenant()), byTenant(tasks.countByTenant()),
				users.findAll().stream().filter(u -> u.getTenantId() != null).collect(Collectors.groupingBy(AppUser::getTenantId)));
	}

	private TenantView view(Tenant t, Counts c) {
		List<AppUser> us = c.users().getOrDefault(t.getId(), List.of());
		Object[] a = c.activities().get(t.getId());
		return new TenantView(t.getId(), t.getName(), t.getCode(), t.isActive(), t.getCreatedAt(), us.size(),
				us.stream().filter(u -> u.getRole() == Role.TENANT_ADMIN).count(), c.of(c.customers(), t.getId()),
				a == null ? 0 : ((Number) a[1]).longValue(), a == null || a[2] == null ? 0 : ((Number) a[2]).longValue(),
				c.of(c.products(), t.getId()), c.of(c.tasks(), t.getId()), a == null ? null : (LocalDateTime) a[3]);
	}

	private static BigDecimal nz(BigDecimal v) {
		return v == null ? BigDecimal.ZERO : v;
	}

	private Tenant find(Long id) {
		return tenants.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@GetMapping
	public List<TenantView> list() {
		Counts c = counts();
		return tenants.findAll(Sort.by("id")).stream().map(t -> view(t, c)).toList();
	}

	/** The details page shows the latest customers and activities; the totals are in the tenant view. */
	private static final int DETAIL_ROWS = 200;

	/** Everything about one tenant: users and hierarchy, customers, activities, products, tasks and sales figures. */
	@GetMapping("/{id}/details")
	public TenantDetail details(@PathVariable Long id) {
		Tenant t = find(id);
		org.springframework.data.domain.PageRequest latest = org.springframework.data.domain.PageRequest.of(0, DETAIL_ROWS);
		TenantData d = new TenantData(users.findByTenantId(id),
				customers.findByTenantId(id, latest.withSort(Sort.by("id").descending())),
				activities.findByTenantIdOrderByActivityTimeDesc(id, latest),
				products.findByTenantIdOrderByNameAsc(id), tasks.findByTenantId(id));
		Map<Long, String> roleNames = teamRoles.findByTenantIdOrderByNameAsc(id).stream()
				.collect(Collectors.toMap(TeamRole::getId, TeamRole::getName));
		List<UserView> uv = d.users().stream().map(u -> UserController.view(u, roleNames)).toList();

		// task progress is worked out the same way the tenant's own users see it
		Scope scope = new Scope(access.current(), id, d.users().stream().map(AppUser::getId).collect(Collectors.toSet()), d.users());
		List<Activity> sales = activities.findByTenantIdAndProductIdIsNotNull(id); // light records, all of them
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
		return new TenantDetail(view(t, counts()), summary, uv, d.customers(), activitySearch.rows(d.activities()),
				d.products(), rows);
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
		String adminEmail = com.hz.crm.user.LoginIdentifiers.normalizeEmail(req.adminEmail());
		String adminMobile = com.hz.crm.user.LoginIdentifiers.normalizeMobile(req.adminMobile());
		identifiers.checkFree(adminEmail, adminMobile, null);
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
		admin.setEmail(adminEmail);
		admin.setMobile(adminMobile);
		users.save(admin);
		return view(t, counts());
	}

	@PutMapping("/{id}/active")
	public TenantView setActive(@PathVariable Long id, @RequestBody ActiveRequest req) {
		Tenant t = find(id);
		t.setActive(req.active());
		return view(tenants.save(t), counts());
	}
}
