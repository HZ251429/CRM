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
import com.hz.crm.deal.Deal;
import com.hz.crm.deal.DealRepository;
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

	public record TenantView(Long id, String name, String code, boolean active, LocalDateTime createdAt,
			long userCount, long customerCount, long dealCount, long activityCount, BigDecimal pipelineValue,
			BigDecimal wonValue) {
	}

	public record TenantDetail(TenantView tenant, List<UserView> users, List<Customer> customers, List<Deal> deals,
			List<Activity> activities) {
	}

	public record CreateTenantRequest(@NotBlank String name, @NotBlank @Size(max = 5) String code,
			@NotBlank String adminUsername, @NotBlank @Size(min = 6) String adminPassword, String adminFullName) {
	}

	public record ActiveRequest(boolean active) {
	}

	private record TenantData(List<AppUser> users, List<Customer> customers, List<Deal> deals,
			List<Activity> activities) {
	}

	private final TenantRepository tenants;
	private final AppUserRepository users;
	private final CustomerRepository customers;
	private final DealRepository deals;
	private final ActivityRepository activities;
	private final PasswordEncoder encoder;
	private final TeamRoleRepository teamRoles;

	public TenantAdminController(TenantRepository tenants, AppUserRepository users, CustomerRepository customers,
			DealRepository deals, ActivityRepository activities, PasswordEncoder encoder,
			TeamRoleRepository teamRoles) {
		this.teamRoles = teamRoles;
		this.tenants = tenants;
		this.users = users;
		this.customers = customers;
		this.deals = deals;
		this.activities = activities;
		this.encoder = encoder;
	}

	private TenantData load(Long tenantId) {
		List<AppUser> us = users.findByTenantId(tenantId);
		List<Long> ids = us.stream().map(AppUser::getId).toList();
		if (ids.isEmpty()) {
			return new TenantData(us, List.of(), List.of(), List.of());
		}
		return new TenantData(us, customers.findByTenantIdAndOwnerIdIn(tenantId, ids, Sort.by("id").descending()),
				deals.findByTenantIdAndOwnerIdIn(tenantId, ids), activities.findByTenantIdAndOwnerIdIn(tenantId, ids));
	}

	private TenantView view(Tenant t, TenantData d) {
		BigDecimal pipeline = BigDecimal.ZERO;
		BigDecimal won = BigDecimal.ZERO;
		for (Deal deal : d.deals()) {
			BigDecimal amt = deal.getAmount() == null ? BigDecimal.ZERO : deal.getAmount();
			if ("WON".equals(deal.getStage())) {
				won = won.add(amt);
			} else if (!"LOST".equals(deal.getStage())) {
				pipeline = pipeline.add(amt);
			}
		}
		return new TenantView(t.getId(), t.getName(), t.getCode(), t.isActive(), t.getCreatedAt(), d.users().size(),
				d.customers().size(), d.deals().size(), d.activities().size(), pipeline, won);
	}

	private Tenant find(Long id) {
		return tenants.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@GetMapping
	public List<TenantView> list() {
		return tenants.findAll(Sort.by("id")).stream().map(t -> view(t, load(t.getId()))).toList();
	}

	/** Everything about one tenant: users and hierarchy, customers, deals and activities. */
	@GetMapping("/{id}/details")
	public TenantDetail details(@PathVariable Long id) {
		Tenant t = find(id);
		TenantData d = load(id);
		Map<Long, String> roleNames = teamRoles.findByTenantIdOrderByNameAsc(id).stream()
				.collect(Collectors.toMap(TeamRole::getId, TeamRole::getName));
		List<UserView> uv = d.users().stream().map(u -> UserController.view(u, roleNames)).toList();
		return new TenantDetail(view(t, d), uv, d.customers(), d.deals(), d.activities());
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
