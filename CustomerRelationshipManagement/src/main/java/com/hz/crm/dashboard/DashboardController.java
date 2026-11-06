package com.hz.crm.dashboard;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
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
import com.hz.crm.security.AccessService;
import com.hz.crm.security.AccessService.Scope;
import com.hz.crm.task.SalesTask;
import com.hz.crm.task.SalesTaskRepository;
import com.hz.crm.task.TaskProgressService;
import com.hz.crm.user.AppUser;
import com.hz.crm.user.Role;

/**
 * Dashboard numbers. A "deal" is an activity that recorded a sale (a product with units sold). Sales figures are
 * rolled up over the user and everyone below them; task figures are the user's own tasks.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

	/** totals for one period or one chart bucket */
	public record Totals(long deals, long units, BigDecimal amount) {
		static Totals of(Collection<Activity> sales) {
			return new Totals(sales.size(), sales.stream().mapToLong(a -> a.getUnitsSold() == null ? 0 : a.getUnitsSold()).sum(),
					sales.stream().map(a -> nz(a.getSaleAmount())).reduce(BigDecimal.ZERO, BigDecimal::add));
		}
	}

	public record Point(String key, long deals, long units, BigDecimal amount) {
	}

	private final CustomerRepository customers;
	private final ActivityRepository activities;
	private final SalesTaskRepository tasks;
	private final TaskProgressService progress;
	private final AccessService access;

	public DashboardController(CustomerRepository customers, ActivityRepository activities, SalesTaskRepository tasks,
			TaskProgressService progress, AccessService access) {
		this.customers = customers;
		this.activities = activities;
		this.tasks = tasks;
		this.progress = progress;
		this.access = access;
	}

	private static BigDecimal nz(BigDecimal v) {
		return v == null ? BigDecimal.ZERO : v;
	}

	private static String name(AppUser u) {
		return u.getFullName() != null ? u.getFullName() : u.getUsername();
	}

	/** Tasks that belong to a user: assigned to them, or for the admin (who is never assigned tasks) assigned by them. */
	private static Predicate<SalesTask> tasksOf(AppUser u) {
		return u.getRole() == Role.TENANT_ADMIN ? t -> u.getId().equals(t.getAssignedById())
				: t -> u.getId().equals(t.getAssignedToId());
	}

	private static List<Activity> between(List<Activity> sales, LocalDateTime from) {
		return sales.stream().filter(a -> !a.getActivityTime().isBefore(from)).toList();
	}

	/**
	 * Dashboard of a user (defaults to the current user). Sales include everyone below that user. A user may only
	 * view themselves and people below them.
	 */
	@GetMapping
	public Map<String, Object> dashboard(@RequestParam(required = false) Long userId,
			@RequestParam(defaultValue = "me") String scope) {
		Scope s = access.scope();
		Long target = userId == null ? s.me().getId() : userId;
		if (!s.canSeeOwner(target)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed to view this dashboard");
		}
		AppUser u = s.tenantUsers().stream().filter(x -> x.getId().equals(target)).findFirst().orElseThrow();
		// members without a manager report to the admin, so the admin's team is the whole tenant
		Set<Long> team = u.getRole() == Role.TENANT_ADMIN
				? s.tenantUsers().stream().map(AppUser::getId).collect(Collectors.toSet())
				: access.subtree(target, s.tenantUsers());
		boolean withTeam = "team".equals(scope);
		// sales figures: the user's own, or including everyone below them
		Set<Long> owners = withTeam ? team : Set.of(target);

		List<SalesTask> allTasks = tasks.findByTenantId(s.tenantId());
		List<Activity> allSales = activities.findByTenantIdAndProductIdIsNotNull(s.tenantId());
		Map<Long, Long> sold = progress.soldByTask(s, allTasks, allSales);
		List<SalesTask> mine = allTasks.stream().filter(tasksOf(u)).toList();
		long completed = mine.stream()
				.filter(t -> TaskProgressService.status(t, sold.getOrDefault(t.getId(), 0L)) == TaskProgressService.Status.COMPLETED)
				.count();
		// units still to be sold on the user's tasks
		long unitsAssigned = mine.stream().mapToLong(SalesTask::getUnits).sum();
		// a member's balance is what is still theirs to sell: units split to their team are no longer theirs.
		// the admin hands everything down, so for the admin it is everything still unsold on the tasks they assigned.
		Map<Long, Long> own = progress.ownBalance(allTasks, sold);
		long unitsBalance = mine.stream().mapToLong(t -> u.getRole() == Role.TENANT_ADMIN
				? Math.max(0, t.getUnits() - sold.getOrDefault(t.getId(), 0L))
				: own.getOrDefault(t.getId(), 0L)).sum();
		long unitsSplit = u.getRole() == Role.TENANT_ADMIN ? 0 : allTasks.stream()
				.filter(t -> t.getParentId() != null && mine.stream().anyMatch(m -> m.getId().equals(t.getParentId())))
				.mapToLong(SalesTask::getUnits).sum();

		List<Activity> sales = allSales.stream()
				.filter(a -> owners.contains(a.getOwnerId()) && a.getActivityTime() != null).toList();
		Totals total = Totals.of(sales);

		LocalDate today = LocalDate.now();
		Map<String, Totals> periods = new LinkedHashMap<>();
		periods.put("day", Totals.of(between(sales, today.atStartOfDay())));
		periods.put("week", Totals.of(between(sales, today.with(DayOfWeek.MONDAY).atStartOfDay())));
		periods.put("month", Totals.of(between(sales, today.withDayOfMonth(1).atStartOfDay())));
		periods.put("year", Totals.of(between(sales, today.withDayOfYear(1).atStartOfDay())));

		Map<String, List<Point>> series = new LinkedHashMap<>();
		series.put("week", daily(sales, today, 7));
		series.put("month", daily(sales, today, 30));
		series.put("year", monthly(sales, YearMonth.from(today), 12));

		Map<String, Object> r = new LinkedHashMap<>();
		r.put("userId", target);
		r.put("userName", name(u));
		r.put("role", u.getRole());
		r.put("teamSize", team.size() - 1);
		r.put("scope", withTeam ? "team" : "me");
		r.put("unitsAssigned", unitsAssigned);
		r.put("unitsBalance", unitsBalance);
		r.put("unitsSplit", unitsSplit);
		r.put("totalTasks", mine.size());
		r.put("openTasks", mine.size() - completed);
		r.put("completedTasks", completed);
		r.put("unitsSold", total.units());
		r.put("salesAmount", total.amount());
		r.put("creditOutstanding", sales.stream().map(a -> nz(a.getCreditAmount())).reduce(BigDecimal.ZERO, BigDecimal::add));
		r.put("deals", periods);
		r.put("series", series);
		return r;
	}

	private static List<Point> daily(List<Activity> sales, LocalDate today, int days) {
		Map<LocalDate, List<Activity>> byDay = sales.stream()
				.collect(Collectors.groupingBy(a -> a.getActivityTime().toLocalDate()));
		List<Point> points = new ArrayList<>();
		for (LocalDate d = today.minusDays(days - 1L); !d.isAfter(today); d = d.plusDays(1)) {
			Totals t = Totals.of(byDay.getOrDefault(d, List.of()));
			points.add(new Point(d.toString(), t.deals(), t.units(), t.amount()));
		}
		return points;
	}

	private static List<Point> monthly(List<Activity> sales, YearMonth current, int months) {
		Map<YearMonth, List<Activity>> byMonth = sales.stream()
				.collect(Collectors.groupingBy(a -> YearMonth.from(a.getActivityTime())));
		List<Point> points = new ArrayList<>();
		for (YearMonth m = current.minusMonths(months - 1L); !m.isAfter(current); m = m.plusMonths(1)) {
			Totals t = Totals.of(byMonth.getOrDefault(m, List.of()));
			points.add(new Point(m.toString(), t.deals(), t.units(), t.amount()));
		}
		return points;
	}

	/** One row per visible user (self + everyone below) with that user's own numbers. */
	@GetMapping("/team")
	public List<Map<String, Object>> team() {
		Scope s = access.scope();
		Set<Long> owners = s.ownerIds();
		Map<Long, Long> customerCount = customers.findByTenantIdAndOwnerIdIn(s.tenantId(), owners, Sort.unsorted())
				.stream().collect(Collectors.groupingBy(Customer::getOwnerId, Collectors.counting()));
		List<Activity> acts = activities.findByTenantIdAndOwnerIdIn(s.tenantId(), owners);
		Map<Long, List<Activity>> actsBy = acts.stream().collect(Collectors.groupingBy(Activity::getOwnerId));
		List<SalesTask> allTasks = tasks.findByTenantId(s.tenantId());
		Map<Long, Long> sold = progress.soldByTask(s, allTasks,
				activities.findByTenantIdAndProductIdIsNotNull(s.tenantId()));
		Map<Long, Long> ownBal = progress.ownBalance(allTasks, sold);
		LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);

		List<Map<String, Object>> rows = new ArrayList<>();
		for (AppUser u : s.tenantUsers()) {
			if (!owners.contains(u.getId())) {
				continue;
			}
			List<Activity> own = actsBy.getOrDefault(u.getId(), List.of());
			List<Activity> ownSales = own.stream().filter(a -> a.getProductId() != null).toList();
			List<SalesTask> mine = allTasks.stream().filter(tasksOf(u)).toList();
			long open = mine.stream().filter(t -> TaskProgressService.status(t, sold.getOrDefault(t.getId(), 0L))
					!= TaskProgressService.Status.COMPLETED).count();
			Totals t = Totals.of(ownSales);

			Map<String, Object> r = new LinkedHashMap<>();
			r.put("userId", u.getId());
			r.put("fullName", name(u));
			r.put("username", u.getUsername());
			r.put("role", u.getRole());
			r.put("managerId", u.getManagerId());
			r.put("active", u.isActive());
			r.put("totalCustomers", customerCount.getOrDefault(u.getId(), 0L));
			r.put("totalTasks", mine.size());
			r.put("openTasks", open);
			r.put("unitsBalance", mine.stream().mapToLong(x -> u.getRole() == Role.TENANT_ADMIN
					? Math.max(0, x.getUnits() - sold.getOrDefault(x.getId(), 0L))
					: ownBal.getOrDefault(x.getId(), 0L)).sum());
			r.put("deals", t.deals());
			r.put("unitsSold", t.units());
			r.put("salesAmount", t.amount());
			r.put("creditOutstanding", ownSales.stream().map(a -> nz(a.getCreditAmount())).reduce(BigDecimal.ZERO, BigDecimal::add));
			r.put("activitiesThisWeek", own.stream()
					.filter(a -> a.getActivityTime() != null && a.getActivityTime().isAfter(weekAgo)).count());
			rows.add(r);
		}
		return rows;
	}
}
