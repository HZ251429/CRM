package com.hz.crm.task;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.hz.crm.activity.Activity;
import com.hz.crm.security.AccessService;
import com.hz.crm.security.AccessService.Scope;

/** Works out how many units of each sales task have been sold, and the task's status. */
@Service
public class TaskProgressService {

	public enum Status { NOT_STARTED, IN_PROGRESS, COMPLETED }

	private final AccessService access;

	public TaskProgressService(AccessService access) {
		this.access = access;
	}

	public static Status status(SalesTask t, long sold) {
		return sold >= t.getUnits() ? Status.COMPLETED : sold > 0 ? Status.IN_PROGRESS : Status.NOT_STARTED;
	}

	/**
	 * Units still to be sold by the assignee themselves ("balance in their name"). Units split to the team are no
	 * longer the assignee's: they are kept by the sub-tasks. Sales by the team first fill the sub-tasks (up to each
	 * sub-task's units); anything beyond that counts toward the units the assignee kept.
	 *
	 * @param sold units sold per task id, from {@link #soldByTask}
	 */
	public Map<Long, Long> ownBalance(List<SalesTask> all, Map<Long, Long> sold) {
		Map<Long, List<SalesTask>> children = all.stream().filter(t -> t.getParentId() != null)
				.collect(Collectors.groupingBy(SalesTask::getParentId));
		Map<Long, Long> balance = new HashMap<>();
		for (SalesTask t : all) {
			List<SalesTask> kids = children.getOrDefault(t.getId(), List.of());
			long split = kids.stream().mapToLong(SalesTask::getUnits).sum();
			long coveredByKids = kids.stream()
					.mapToLong(k -> Math.min(k.getUnits(), sold.getOrDefault(k.getId(), 0L))).sum();
			long kept = Math.max(0, t.getUnits() - split);
			long soldOnKept = Math.max(0, sold.getOrDefault(t.getId(), 0L) - coveredByKids);
			balance.put(t.getId(), Math.max(0, kept - soldOnKept));
		}
		return balance;
	}

	/**
	 * The tasks needed to work out progress of {@code wanted}: a task's count depends on every task of the same
	 * person and product (sales fill the oldest first), and its balance depends on its sub-tasks. Counting only these
	 * instead of the whole tenant keeps a member's pages fast in a large company.
	 */
	public List<SalesTask> related(List<SalesTask> all, java.util.Collection<SalesTask> wanted) {
		Set<Long> wantedIds = wanted.stream().map(SalesTask::getId).collect(Collectors.toSet());
		Set<String> keys = new java.util.HashSet<>();
		for (SalesTask t : all) {
			if (wantedIds.contains(t.getId()) || (t.getParentId() != null && wantedIds.contains(t.getParentId()))) {
				keys.add(t.getAssignedToId() + ":" + t.getProductId());
			}
		}
		return all.stream().filter(t -> keys.contains(t.getAssignedToId() + ":" + t.getProductId())).toList();
	}

	/** Sales count from the start of the day the task was assigned. */
	private static LocalDateTime countsFrom(SalesTask t) {
		return t.getCreatedAt().toLocalDate().atStartOfDay();
	}

	/**
	 * Units sold per task id. Sales by a person and everyone below them count toward that person's tasks for the
	 * product, but each sale is counted only once per person: it fills their oldest unfinished task first and the
	 * rest flows to the next one. A sale only counts toward tasks that existed on the day of the sale. Units sold
	 * beyond all targets are added to the newest task the sale could count toward, so over-achievement stays visible.
	 * Parent and sub-tasks belong to different people, so a leader's task still rolls up the team's sales.
	 *
	 * @param all all tasks of the tenant
	 * @param sales all activities of the tenant that recorded a sale
	 */
	public Map<Long, Long> soldByTask(Scope s, List<SalesTask> all, List<Activity> sales) {
		Map<Long, Long> sold = new HashMap<>();
		allocation(s, all, sales).forEach((taskId, parts) ->
				sold.put(taskId, parts.values().stream().mapToLong(Long::longValue).sum()));
		return sold;
	}

	/**
	 * Same counting as {@link #soldByTask}, but says which sales went to each task: task id -> (activity id -> units
	 * of that sale counted toward the task). Used to work out each task's share of the sale amounts and credit.
	 */
	public Map<Long, Map<Long, Long>> allocation(Scope s, List<SalesTask> all, List<Activity> sales) {
		Map<Long, Map<Long, Long>> parts = new HashMap<>();
		Map<String, List<SalesTask>> byPersonAndProduct = all.stream()
				.collect(Collectors.groupingBy(t -> t.getAssignedToId() + ":" + t.getProductId()));
		Comparator<SalesTask> oldestFirst = Comparator.comparing(SalesTask::getCreatedAt).thenComparing(SalesTask::getId);
		Comparator<Activity> bySaleTime = Comparator.comparing(Activity::getActivityTime).thenComparing(Activity::getId);

		// sales indexed once by product and seller, so each group only looks at its own team's sales
		Map<Long, Map<Long, List<Activity>>> salesByProductAndOwner = sales.stream()
				.filter(a -> a.getProductId() != null && a.getOwnerId() != null && a.getActivityTime() != null
						&& a.getUnitsSold() != null)
				.collect(Collectors.groupingBy(Activity::getProductId, Collectors.groupingBy(Activity::getOwnerId)));
		Map<Long, Set<Long>> teams = new HashMap<>();

		for (List<SalesTask> group : byPersonAndProduct.values()) {
			List<SalesTask> ordered = group.stream().sorted(oldestFirst).toList();
			Map<Long, Long> filled = new HashMap<>();
			ordered.forEach(t -> { filled.put(t.getId(), 0L); parts.put(t.getId(), new java.util.LinkedHashMap<>()); });
			Long productId = ordered.get(0).getProductId();
			Set<Long> team = teams.computeIfAbsent(ordered.get(0).getAssignedToId(),
					id -> access.subtree(id, s.tenantUsers()));
			Map<Long, List<Activity>> productSales = salesByProductAndOwner.getOrDefault(productId, Map.of());
			List<Activity> stream = new java.util.ArrayList<>();
			for (Long member : team) {
				stream.addAll(productSales.getOrDefault(member, List.of()));
			}
			stream.sort(bySaleTime);

			for (Activity a : stream) {
				long left = a.getUnitsSold();
				SalesTask newestEligible = null;
				for (SalesTask t : ordered) {
					if (a.getActivityTime().isBefore(countsFrom(t))) {
						break; // this and all newer tasks did not exist yet on the day of the sale
					}
					newestEligible = t;
					long room = t.getUnits() - filled.get(t.getId());
					if (room > 0 && left > 0) {
						long take = Math.min(room, left);
						filled.merge(t.getId(), take, Long::sum);
						parts.get(t.getId()).merge(a.getId(), take, Long::sum);
						left -= take;
					}
				}
				if (left > 0 && newestEligible != null) {
					filled.merge(newestEligible.getId(), left, Long::sum);
					parts.get(newestEligible.getId()).merge(a.getId(), left, Long::sum);
				}
			}
		}
		return parts;
	}
}