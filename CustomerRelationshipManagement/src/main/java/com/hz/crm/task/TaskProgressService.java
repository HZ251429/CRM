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
}
