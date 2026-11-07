package com.hz.crm.stock;

import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.hz.crm.activity.Activity;
import com.hz.crm.activity.ActivityRepository;
import com.hz.crm.task.SalesTask;
import com.hz.crm.task.SalesTaskRepository;

/**
 * Stock per product. Units are taken out of stock when the admin assigns them in a task (a task created from scratch,
 * not a split, since a split only passes on units that were already assigned).
 */
@Service
public class StockService {

	/** total = stock received; assigned = units in tasks; left = still free to assign; sold = units sold in activities */
	public record Level(long total, long assigned, long left, long sold) {
	}

	private final StockEntryRepository entries;
	private final SalesTaskRepository tasks;
	private final ActivityRepository activities;

	public StockService(StockEntryRepository entries, SalesTaskRepository tasks, ActivityRepository activities) {
		this.entries = entries;
		this.tasks = tasks;
		this.activities = activities;
	}

	/** Stock levels of every product of the tenant that has stock or tasks (product id -> level). */
	public Map<Long, Level> levels(Long tenantId) {
		Map<Long, Long> total = entries.findByTenantId(tenantId).stream()
				.collect(Collectors.groupingBy(StockEntry::getProductId, Collectors.summingLong(StockEntry::getQuantity)));
		Map<Long, Long> assigned = tasks.findByTenantId(tenantId).stream().filter(t -> t.getParentId() == null && t.isFromStock())
				.collect(Collectors.groupingBy(SalesTask::getProductId, Collectors.summingLong(SalesTask::getUnits)));
		Map<Long, Long> sold = activities.findByTenantIdAndProductIdIsNotNull(tenantId).stream()
				.filter(a -> a.getUnitsSold() != null)
				.collect(Collectors.groupingBy(Activity::getProductId, Collectors.summingLong(Activity::getUnitsSold)));
		Map<Long, Level> out = new java.util.HashMap<>();
		java.util.Set<Long> ids = new java.util.HashSet<>(total.keySet());
		ids.addAll(assigned.keySet());
		ids.addAll(sold.keySet());
		for (Long id : ids) {
			long t = total.getOrDefault(id, 0L);
			long a = assigned.getOrDefault(id, 0L);
			out.put(id, new Level(t, a, t - a, sold.getOrDefault(id, 0L)));
		}
		return out;
	}

	public Level level(Long tenantId, Long productId) {
		return levels(tenantId).getOrDefault(productId, new Level(0, 0, 0, 0));
	}
}
