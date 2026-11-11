package com.hz.crm.activity;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface ActivityRepository extends JpaRepository<Activity, Long>, JpaSpecificationExecutor<Activity> {

	List<Activity> findByTenantIdAndOwnerIdIn(Long tenantId, Collection<Long> owners);

	/**
	 * All sales of a tenant, as light read-only records (no notes or other text): only the fields that progress
	 * counting, stock and sale totals need. Much cheaper than loading full entities for large tenants.
	 */
	@Query("select new com.hz.crm.activity.Activity(a.id, a.tenantId, a.ownerId, a.customerId, a.productId, a.unitsSold,"
			+ " a.saleAmount, a.amountPaid, a.activityTime) from Activity a where a.tenantId = ?1 and a.productId is not null")
	List<Activity> findByTenantIdAndProductIdIsNotNull(Long tenantId);

	/** The same light sale records, limited to some owners (for a member's own view). */
	@Query("select new com.hz.crm.activity.Activity(a.id, a.tenantId, a.ownerId, a.customerId, a.productId, a.unitsSold,"
			+ " a.saleAmount, a.amountPaid, a.activityTime) from Activity a"
			+ " where a.tenantId = ?1 and a.ownerId in ?2 and a.productId is not null")
	List<Activity> findSales(Long tenantId, Collection<Long> owners);

	List<Activity> findByTenantIdAndOwnerIdInAndCustomerId(Long tenantId, Collection<Long> owners, Long customerId);

	/** Per customer: number of activities, last contact, number of sales, sale total and credit still owed. */
	@Query("select a.customerId, count(a), max(a.activityTime), count(a.productId), coalesce(sum(a.saleAmount), 0),"
			+ " coalesce(sum(case when a.amountPaid < a.saleAmount then a.saleAmount - a.amountPaid else 0 end), 0)"
			+ " from Activity a where a.tenantId = ?1 and a.ownerId in ?2 and a.customerId in ?3 group by a.customerId")
	List<Object[]> statsByCustomer(Long tenantId, Collection<Long> owners, Collection<Long> customerIds);

	/** Activities (meetings, calls, ...; not sales) logged per owner in [from, to): [ownerId, count]. */
	@Query("select a.ownerId, count(a) from Activity a where a.tenantId = ?1 and a.ownerId in ?2"
			+ " and a.productId is null and a.activityTime >= ?3 and a.activityTime < ?4 group by a.ownerId")
	List<Object[]> countByOwnerBetween(Long tenantId, Collection<Long> owners, java.time.LocalDateTime from,
			java.time.LocalDateTime to);

	/** Per tenant: [tenantId, activities, activities since the time, last activity time]. */
	@Query("select a.tenantId, count(a), sum(case when a.activityTime > ?1 then 1 else 0 end), max(a.activityTime)"
			+ " from Activity a group by a.tenantId")
	List<Object[]> tenantStats(java.time.LocalDateTime since);

	List<Activity> findByTenantIdOrderByActivityTimeDesc(Long tenantId, org.springframework.data.domain.Pageable page);

	/** Follow-ups of these people, open (not done) or done, soonest first. */
	@Query("select a from Activity a where a.tenantId = ?1 and a.ownerId in ?2 and a.followUpAt is not null"
			+ " and ((?3 = true and a.followUpDoneAt is null) or (?3 = false and a.followUpDoneAt is not null))"
			+ " order by a.followUpAt")
	List<Activity> findFollowUps(Long tenantId, Collection<Long> owners, boolean open,
			org.springframework.data.domain.Pageable page);

	/** Open follow-ups whose reminder has not gone out, due before the given time (a day ahead covers every option). */
	@Query("select a from Activity a where a.followUpAt is not null and a.followUpDoneAt is null"
			+ " and a.followUpRemindedAt is null and a.followUpAt <= ?1")
	List<Activity> findRemindersDueBy(java.time.LocalDateTime time);

	@Transactional
	@Modifying
	@Query("delete from Activity a where a.customerId = ?1")
	void deleteByCustomerId(Long customerId);
}
