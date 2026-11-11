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
import com.hz.crm.product.Product;
import com.hz.crm.product.ProductRepository;
import com.hz.crm.security.AccessService;
import com.hz.crm.security.AccessService.Scope;
import com.hz.crm.task.SalesTask;
import com.hz.crm.task.SalesTaskRepository;
import com.hz.crm.task.TaskProgressService;
import com.hz.crm.user.AppUser;
import com.hz.crm.user.Role;
import com.hz.crm.user.TeamRole;
import com.hz.crm.user.TeamRoleRepository;

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

	/** one chart bucket: totals for the user and their team, and the part that is the user's own sales */
	public record Point(String key, long deals, long units, BigDecimal amount, long dealsMine, long unitsMine,
			BigDecimal amountMine) {
	}

	private final CustomerRepository customers;
	private final ActivityRepository activities;
	private final SalesTaskRepository tasks;
	private final TaskProgressService progress;
	private final AccessService access;
	private final ProductRepository products;
	private final TeamRoleRepository teamRoles;

	public DashboardController(CustomerRepository customers, ActivityRepository activities, SalesTaskRepository tasks,
			TaskProgressService progress, AccessService access, ProductRepository products, TeamRoleRepository teamRoles) {
		this.teamRoles = teamRoles;
		this.products = products;
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
		List<SalesTask> mine = allTasks.stream().filter(tasksOf(u)).toList();
		// only the user's team's sales and the tasks needed to count the user's tasks (not the whole company)
		List<Activity> allSales = u.getRole() == Role.TENANT_ADMIN
				? activities.findByTenantIdAndProductIdIsNotNull(s.tenantId())
				: activities.findSales(s.tenantId(), team);
		List<SalesTask> related = progress.related(allTasks, mine);
		Map<Long, Long> sold = progress.soldByTask(s, related, allSales);
		long completed = mine.stream()
				.filter(t -> TaskProgressService.status(t, sold.getOrDefault(t.getId(), 0L)) == TaskProgressService.Status.COMPLETED)
				.count();
		// units still to be sold on the user's tasks
		long unitsAssigned = mine.stream().mapToLong(SalesTask::getUnits).sum();
		// a member's balance is what is still theirs to sell: units split to their team are no longer theirs.
		// the admin hands everything down, so for the admin it is everything still unsold on the tasks they assigned.
		Map<Long, Long> own = progress.ownBalance(related, sold);
		long unitsBalance = mine.stream().mapToLong(t -> u.getRole() == Role.TENANT_ADMIN
				? Math.max(0, t.getUnits() - sold.getOrDefault(t.getId(), 0L))
				: own.getOrDefault(t.getId(), 0L)).sum();
		Set<Long> mineIds = mine.stream().map(SalesTask::getId).collect(Collectors.toSet());
		long unitsSplit = u.getRole() == Role.TENANT_ADMIN ? 0 : allTasks.stream()
				.filter(t -> t.getParentId() != null && mineIds.contains(t.getParentId()))
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
		// the chart always shows the user and their team (stacked), whatever the Only me / With team switch says
		List<Activity> teamSales = allSales.stream()
				.filter(a -> team.contains(a.getOwnerId()) && a.getActivityTime() != null).toList();
		series.put("week", daily(teamSales, target, today, 7));
		series.put("month", daily(teamSales, target, today, 30));
		series.put("year", monthly(teamSales, target, YearMonth.from(today), 12));

		Map<String, Object> r = new LinkedHashMap<>();
		r.put("userId", target);
		r.put("userName", name(u));
		r.put("role", u.getRole());
		r.put("teamSize", team.size() - 1);
		r.put("scope", withTeam ? "team" : "me");
		r.put("unitsAssigned", unitsAssigned);
		r.put("unitsBalance", unitsBalance);
		// units handed down to the team that are not sold yet: everything unsold on the user's tasks minus what is
		// still the user's own to sell (for the admin, who sells nothing themselves, that is everything unsold)
		long unsold = mine.stream().mapToLong(t -> Math.max(0, t.getUnits() - sold.getOrDefault(t.getId(), 0L))).sum();
		// units sold by the user themselves and by everyone below them, always both (independent of the scope switch)
		long soldMine = allSales.stream().filter(a -> target.equals(a.getOwnerId()) && a.getUnitsSold() != null)
				.mapToLong(Activity::getUnitsSold).sum();
		long soldTeam = allSales.stream().filter(a -> team.contains(a.getOwnerId()) && !target.equals(a.getOwnerId())
				&& a.getUnitsSold() != null).mapToLong(Activity::getUnitsSold).sum();
		r.put("unitsSoldMine", soldMine);
		r.put("unitsSoldTeam", soldTeam);
		r.put("unitsTeamPending", u.getRole() == Role.TENANT_ADMIN ? unsold : Math.max(0, unsold - unitsBalance));
		r.put("unitsSplit", unitsSplit);
		r.put("totalTasks", mine.size());
		r.put("openTasks", mine.size() - completed);
		r.put("completedTasks", completed);
		r.put("unitsSold", total.units());
		r.put("salesAmount", total.amount());
		r.put("creditOutstanding", sales.stream().map(a -> nz(a.getCreditAmount())).reduce(BigDecimal.ZERO, BigDecimal::add));
		r.put("deals", periods);
		r.put("series", series);

		// task status breakdown for the progress card
		Map<String, Long> statusCounts = new LinkedHashMap<>();
		for (TaskProgressService.Status st : TaskProgressService.Status.values()) {
			statusCounts.put(st.name(), mine.stream()
					.filter(t -> TaskProgressService.status(t, sold.getOrDefault(t.getId(), 0L)) == st).count());
		}
		r.put("taskStatus", statusCounts);
		r.put("unitsSoldOnTasks", mine.stream().mapToLong(t -> Math.min(t.getUnits(), sold.getOrDefault(t.getId(), 0L))).sum());

		// the latest few deals for the "recent deals" list
		Map<Long, String> userNames = s.tenantUsers().stream().collect(Collectors.toMap(AppUser::getId, DashboardController::name));
		Map<Long, String> productNames = products.findByTenantIdOrderByNameAsc(s.tenantId()).stream()
				.collect(Collectors.toMap(Product::getId, Product::getName));
		List<Activity> latest = sales.stream()
				.sorted(java.util.Comparator.comparing(Activity::getActivityTime).reversed()).limit(6).toList();
		Map<Long, String> customerNames = customers.findAllById(latest.stream().map(Activity::getCustomerId).toList())
				.stream().collect(Collectors.toMap(Customer::getId, Customer::getName));
		r.put("recentDeals", latest.stream().map(a -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", a.getId());
			m.put("when", a.getActivityTime());
			m.put("member", userNames.getOrDefault(a.getOwnerId(), ""));
			m.put("customer", customerNames.getOrDefault(a.getCustomerId(), ""));
			m.put("product", productNames.getOrDefault(a.getProductId(), ""));
			m.put("units", a.getUnitsSold());
			m.put("amount", a.getSaleAmount());
			m.put("credit", a.getCreditAmount());
			return m;
		}).toList());
		return r;
	}

	private static Point point(String key, List<Activity> bucket, Long me) {
		Totals all = Totals.of(bucket);
		Totals mine = Totals.of(bucket.stream().filter(a -> me.equals(a.getOwnerId())).toList());
		return new Point(key, all.deals(), all.units(), all.amount(), mine.deals(), mine.units(), mine.amount());
	}

	private static List<Point> daily(List<Activity> sales, Long me, LocalDate today, int days) {
		Map<LocalDate, List<Activity>> byDay = sales.stream()
				.collect(Collectors.groupingBy(a -> a.getActivityTime().toLocalDate()));
		List<Point> points = new ArrayList<>();
		for (LocalDate d = today.minusDays(days - 1L); !d.isAfter(today); d = d.plusDays(1)) {
			points.add(point(d.toString(), byDay.getOrDefault(d, List.of()), me));
		}
		return points;
	}

	private static List<Point> monthly(List<Activity> sales, Long me, YearMonth current, int months) {
		Map<YearMonth, List<Activity>> byMonth = sales.stream()
				.collect(Collectors.groupingBy(a -> YearMonth.from(a.getActivityTime())));
		List<Point> points = new ArrayList<>();
		for (YearMonth m = current.minusMonths(months - 1L); !m.isAfter(current); m = m.plusMonths(1)) {
			points.add(point(m.toString(), byMonth.getOrDefault(m, List.of()), me));
		}
		return points;
	}

	/**
	 * One row per visible user (self + everyone below) with that user's own numbers. With from/to (days, both
	 * included) the numbers cover that period: tasks assigned, sales and activities in it, and customers added in it.
	 * Without them everything is counted.
	 */
	@GetMapping("/team")
	public List<Map<String, Object>> team(@RequestParam(required = false) LocalDate from,
			@RequestParam(required = false) LocalDate to) {
		Scope s = access.scope();
		Set<Long> owners = s.ownerIds();
		LocalDateTime start = from == null ? LocalDateTime.of(1900, 1, 1, 0, 0) : from.atStartOfDay();
		LocalDateTime end = to == null ? LocalDateTime.of(9999, 1, 1, 0, 0) : to.plusDays(1).atStartOfDay();
		java.util.function.Predicate<LocalDateTime> inPeriod = t -> t != null && !t.isBefore(start) && t.isBefore(end);
		// counted in the database: no need to load every customer and activity of the team
		Map<Long, Long> customerCount = new java.util.HashMap<>();
		customers.countByOwnerBetween(s.tenantId(), owners, start, end)
				.forEach(row -> customerCount.put((Long) row[0], (Long) row[1]));
		Map<Long, Long> activityCount = new java.util.HashMap<>();
		activities.countByOwnerBetween(s.tenantId(), owners, start, end)
				.forEach(row -> activityCount.put((Long) row[0], (Long) row[1]));
		List<Activity> teamSales = s.me().getRole() == Role.TENANT_ADMIN
				? activities.findByTenantIdAndProductIdIsNotNull(s.tenantId())
				: activities.findSales(s.tenantId(), owners);
		Map<Long, List<Activity>> salesBy = teamSales.stream().collect(Collectors.groupingBy(Activity::getOwnerId));
		List<SalesTask> allTasks = tasks.findByTenantId(s.tenantId());
		Map<Long, List<SalesTask>> tasksByAssignee = allTasks.stream().collect(Collectors.groupingBy(SalesTask::getAssignedToId));
		Map<Long, List<SalesTask>> tasksByAssigner = allTasks.stream().collect(Collectors.groupingBy(SalesTask::getAssignedById));
		List<SalesTask> related = progress.related(allTasks, allTasks.stream()
				.filter(x -> owners.contains(x.getAssignedToId()) || owners.contains(x.getAssignedById())).toList());
		Map<Long, Long> sold = progress.soldByTask(s, related, teamSales);
		Map<Long, Long> ownBal = progress.ownBalance(related, sold);
		Map<Long, String> roleNames = teamRoles.findByTenantIdOrderByNameAsc(s.tenantId()).stream()
				.collect(Collectors.toMap(TeamRole::getId, TeamRole::getName));

		List<Map<String, Object>> rows = new ArrayList<>();
		for (AppUser u : s.tenantUsers()) {
			if (!owners.contains(u.getId())) {
				continue;
			}
			// progress is still counted on all tasks; the period only picks which sales and tasks are shown
			List<Activity> ownSales = salesBy.getOrDefault(u.getId(), List.of()).stream()
					.filter(a -> inPeriod.test(a.getActivityTime())).toList();
			List<SalesTask> mine = (u.getRole() == Role.TENANT_ADMIN ? tasksByAssigner : tasksByAssignee)
					.getOrDefault(u.getId(), List.of()).stream().filter(tasksOf(u))
					.filter(x -> inPeriod.test(x.getCreatedAt())).toList();
			long open = mine.stream().filter(t -> TaskProgressService.status(t, sold.getOrDefault(t.getId(), 0L))
					!= TaskProgressService.Status.COMPLETED).count();
			Totals t = Totals.of(ownSales);

			Map<String, Object> r = new LinkedHashMap<>();
			r.put("userId", u.getId());
			r.put("fullName", name(u));
			r.put("username", u.getUsername());
			r.put("role", u.getRole());
			r.put("managerId", u.getManagerId());
			r.put("teamRoleName", u.getTeamRoleId() == null ? null : roleNames.get(u.getTeamRoleId()));
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
			r.put("activities", activityCount.getOrDefault(u.getId(), 0L));
			rows.add(r);
		}
		return rows;
	}
}
