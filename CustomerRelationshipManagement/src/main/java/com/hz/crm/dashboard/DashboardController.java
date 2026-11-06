package com.hz.crm.dashboard;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.hz.crm.activity.Activity;
import com.hz.crm.activity.ActivityRepository;
import com.hz.crm.customer.Customer;
import com.hz.crm.customer.CustomerRepository;
import com.hz.crm.deal.Deal;
import com.hz.crm.deal.DealRepository;
import com.hz.crm.security.AccessService;
import com.hz.crm.security.AccessService.Scope;
import com.hz.crm.user.AppUser;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

	private final CustomerRepository customers;
	private final DealRepository deals;
	private final ActivityRepository activities;
	private final AccessService access;

	public DashboardController(CustomerRepository customers, DealRepository deals, ActivityRepository activities,
			AccessService access) {
		this.customers = customers;
		this.deals = deals;
		this.activities = activities;
		this.access = access;
	}

	private static BigDecimal amount(Deal d) {
		return d.getAmount() == null ? BigDecimal.ZERO : d.getAmount();
	}

	private Map<String, Object> stats(List<Customer> cs, List<Deal> ds, List<Activity> as) {
		Map<String, Long> dealsByStage = new LinkedHashMap<>();
		BigDecimal pipeline = BigDecimal.ZERO;
		BigDecimal won = BigDecimal.ZERO;
		for (Deal d : ds) {
			dealsByStage.merge(d.getStage(), 1L, Long::sum);
			if ("WON".equals(d.getStage())) {
				won = won.add(amount(d));
			} else if (!"LOST".equals(d.getStage())) {
				pipeline = pipeline.add(amount(d));
			}
		}
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("totalCustomers", cs.size());
		r.put("totalDeals", ds.size());
		java.time.LocalDateTime weekAgo = java.time.LocalDateTime.now().minusDays(7);
		r.put("activitiesThisWeek",
				as.stream().filter(a -> a.getActivityTime() != null && a.getActivityTime().isAfter(weekAgo)).count());
		r.put("pipelineValue", pipeline);
		r.put("wonValue", won);
		r.put("dealsByStage", dealsByStage);
		return r;
	}

	private List<Customer> customersOf(Scope s, Collection<Long> owners) {
		return customers.findByTenantIdAndOwnerIdIn(s.tenantId(), owners, Sort.unsorted());
	}

	/**
	 * Dashboard of a user including all of that user's subordinates. Defaults to the
	 * current user. A user may only view themselves and people below them.
	 */
	@GetMapping
	public Map<String, Object> dashboard(@RequestParam(required = false) Long userId) {
		Scope s = access.scope();
		Long target = userId == null ? s.me().getId() : userId;
		if (!s.canSeeOwner(target)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed to view this dashboard");
		}
		Set<Long> owners = access.subtree(target, s.tenantUsers());
		Map<String, Object> r = stats(customersOf(s, owners),
				deals.findByTenantIdAndOwnerIdIn(s.tenantId(), owners),
				activities.findByTenantIdAndOwnerIdIn(s.tenantId(), owners));
		AppUser u = s.tenantUsers().stream().filter(x -> x.getId().equals(target)).findFirst().orElseThrow();
		r.put("userId", target);
		r.put("userName", u.getFullName() != null ? u.getFullName() : u.getUsername());
		r.put("teamSize", owners.size() - 1);
		return r;
	}

	/** One row per visible user (self + subordinates) with that user's own numbers. */
	@GetMapping("/team")
	public List<Map<String, Object>> team() {
		Scope s = access.scope();
		Set<Long> owners = s.ownerIds();
		Map<Long, List<Customer>> cs = customersOf(s, owners).stream()
				.collect(Collectors.groupingBy(Customer::getOwnerId));
		Map<Long, List<Deal>> ds = deals.findByTenantIdAndOwnerIdIn(s.tenantId(), owners).stream()
				.collect(Collectors.groupingBy(Deal::getOwnerId));
		Map<Long, List<Activity>> as = activities.findByTenantIdAndOwnerIdIn(s.tenantId(), owners).stream()
				.collect(Collectors.groupingBy(Activity::getOwnerId));

		List<Map<String, Object>> rows = new ArrayList<>();
		for (AppUser u : s.tenantUsers()) {
			if (!owners.contains(u.getId())) {
				continue;
			}
			Map<String, Object> r = stats(cs.getOrDefault(u.getId(), List.of()), ds.getOrDefault(u.getId(), List.of()),
					as.getOrDefault(u.getId(), List.of()));
			r.remove("dealsByStage");
			r.put("userId", u.getId());
			r.put("fullName", u.getFullName() != null ? u.getFullName() : u.getUsername());
			r.put("username", u.getUsername());
			r.put("role", u.getRole());
			r.put("managerId", u.getManagerId());
			r.put("active", u.isActive());
			rows.add(r);
		}
		return rows;
	}
}