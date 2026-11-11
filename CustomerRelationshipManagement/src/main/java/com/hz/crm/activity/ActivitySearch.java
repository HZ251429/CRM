package com.hz.crm.activity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.hz.crm.customer.Customer;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Activity log search done in the database, one page at a time, with totals for everything that matches.
 * Replaces sending a user's whole activity history to the browser.
 */
@Service
public class ActivitySearch {

	/** What to look for. Owners must already be limited to what the user may see. */
	public record Filter(Long tenantId, Collection<Long> owners, String q, Collection<Long> qOwners,
			Collection<Long> qProducts, String type, boolean salesOnly, boolean creditOnly, LocalDate from, LocalDate to,
			Long customerId, boolean activitiesOnly, Long taskId) {
	}

	/** One row of the log, with the customer's name so the browser does not need the customer list. */
	public record Row(Long id, Long customerId, String customerName, String type, String subject, String attendees,
			String notes, String outcome, Long productId, Integer unitsSold, BigDecimal saleAmount, BigDecimal amountPaid,
			BigDecimal creditAmount, java.time.LocalDateTime activityTime, Long ownerId, Long taskId, String taskTitle,
			String meetingMode, java.time.LocalDateTime followUpAt, String followUpNote, Integer followUpRemindMinutes,
			java.time.LocalDateTime followUpDoneAt) {
	}

	public record Result(List<Row> content, int page, int totalPages, long totalElements, long sales,
			BigDecimal saleTotal, BigDecimal creditTotal) {
	}

	private final EntityManager em;

	public ActivitySearch(EntityManager em) {
		this.em = em;
	}

	private List<Predicate> where(Filter f, CriteriaBuilder cb, CriteriaQuery<?> cq, Root<Activity> a) {
		List<Predicate> p = new ArrayList<>();
		p.add(cb.equal(a.get("tenantId"), f.tenantId()));
		p.add(a.get("ownerId").in(f.owners()));
		if (f.customerId() != null) {
			p.add(cb.equal(a.get("customerId"), f.customerId()));
		}
		if (f.type() != null && !f.type().isBlank()) {
			p.add(cb.equal(a.get("type"), f.type()));
		}
		if (f.activitiesOnly()) {
			p.add(cb.isNull(a.get("productId")));
		}
		if (f.taskId() != null) {
			p.add(cb.equal(a.get("taskId"), f.taskId()));
		}
		if (f.salesOnly() || f.creditOnly()) {
			p.add(cb.isNotNull(a.get("productId")));
		}
		if (f.creditOnly()) {
			p.add(cb.lessThan(a.get("amountPaid"), a.<BigDecimal>get("saleAmount")));
		}
		if (f.from() != null) {
			p.add(cb.greaterThanOrEqualTo(a.get("activityTime"), f.from().atStartOfDay()));
		}
		if (f.to() != null) {
			p.add(cb.lessThan(a.get("activityTime"), f.to().plusDays(1).atStartOfDay()));
		}
		String q = f.q() == null ? "" : f.q().trim().toLowerCase();
		if (!q.isEmpty()) {
			String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
			List<Predicate> any = new ArrayList<>();
			for (String field : List.of("subject", "attendees", "notes", "outcome")) {
				any.add(cb.like(cb.lower(cb.coalesce(a.<String>get(field), "")), like, '\\'));
			}
			// customer name, email or company
			Subquery<Long> customers = cq.subquery(Long.class);
			Root<Customer> c = customers.from(Customer.class);
			Expression<String> fullName = cb.lower(cb.concat(cb.concat(c.get("firstName"), " "), c.get("lastName")));
			customers.select(c.get("id")).where(cb.equal(c.get("tenantId"), f.tenantId()), cb.or(
					cb.like(fullName, like, '\\'),
					cb.like(cb.lower(cb.coalesce(c.<String>get("email"), "")), like, '\\'),
					cb.like(cb.lower(cb.coalesce(c.<String>get("phone"), "")), like, '\\'),
					cb.like(cb.lower(cb.coalesce(c.<String>get("company"), "")), like, '\\')));
			any.add(a.get("customerId").in(customers));
			if (!f.qOwners().isEmpty()) {
				any.add(a.get("ownerId").in(f.qOwners()));
			}
			if (!f.qProducts().isEmpty()) {
				any.add(a.get("productId").in(f.qProducts()));
			}
			p.add(cb.or(any.toArray(Predicate[]::new)));
		}
		return p;
	}

	public Result search(Filter f, int page, int size) {
		CriteriaBuilder cb = em.getCriteriaBuilder();

		// totals over everything that matches
		CriteriaQuery<Object[]> tq = cb.createQuery(Object[].class);
		Root<Activity> ta = tq.from(Activity.class);
		Expression<BigDecimal> credit = cb.<BigDecimal>selectCase()
				.when(cb.lessThan(ta.get("amountPaid"), ta.<BigDecimal>get("saleAmount")),
						cb.diff(ta.<BigDecimal>get("saleAmount"), ta.<BigDecimal>get("amountPaid")))
				.otherwise(BigDecimal.ZERO);
		tq.multiselect(cb.count(ta), cb.count(ta.get("productId")),
				cb.coalesce(cb.sum(ta.<BigDecimal>get("saleAmount")), BigDecimal.ZERO), cb.coalesce(cb.sum(credit), BigDecimal.ZERO))
				.where(where(f, cb, tq, ta).toArray(Predicate[]::new));
		Object[] totals = em.createQuery(tq).getSingleResult();
		long total = ((Number) totals[0]).longValue();

		// one page, newest first
		CriteriaQuery<Activity> q = cb.createQuery(Activity.class);
		Root<Activity> a = q.from(Activity.class);
		q.select(a).where(where(f, cb, q, a).toArray(Predicate[]::new))
				.orderBy(cb.desc(a.get("activityTime")), cb.desc(a.get("id")));
		List<Activity> items = em.createQuery(q).setFirstResult(page * size).setMaxResults(size).getResultList();

		return new Result(rows(items), page, (int) Math.ceil(total / (double) size), total,
				((Number) totals[1]).longValue(), toBig(totals[2]), toBig(totals[3]));
	}

	/** Activities as rows with the customer's name and the task's title (one query each for all of them). */
	public List<Row> rows(List<Activity> items) {
		Set<Long> ids = items.stream().map(Activity::getCustomerId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
		Map<Long, Customer> byId = ids.isEmpty() ? Map.of()
				: em.createQuery("select c from Customer c where c.id in :ids", Customer.class).setParameter("ids", ids)
						.getResultList().stream().collect(Collectors.toMap(Customer::getId, Function.identity()));
		Set<Long> taskIds = items.stream().map(Activity::getTaskId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
		Map<Long, String> taskTitles = new java.util.HashMap<>();
		if (!taskIds.isEmpty()) {
			for (Object[] r : em.createQuery("select t.id, t.title from SalesTask t where t.id in :ids", Object[].class)
					.setParameter("ids", taskIds).getResultList()) {
				taskTitles.put((Long) r[0], r[1] == null ? "Task #" + r[0] : (String) r[1]);
			}
		}
		return items.stream().map(x -> {
			Customer c = x.getCustomerId() == null ? null : byId.get(x.getCustomerId());
			String name = x.getCustomerId() == null ? null : c == null ? "Customer #" + x.getCustomerId()
					: c.getName()
							+ (c.getCompany() == null || c.getCompany().isBlank() ? "" : " · " + c.getCompany());
			return new Row(x.getId(), x.getCustomerId(), name, x.getType(), x.getSubject(), x.getAttendees(), x.getNotes(),
					x.getOutcome(), x.getProductId(), x.getUnitsSold(), x.getSaleAmount(), x.getAmountPaid(),
					x.getCreditAmount(), x.getActivityTime(), x.getOwnerId(), x.getTaskId(),
					x.getTaskId() == null ? null : taskTitles.get(x.getTaskId()), x.getMeetingMode(), x.getFollowUpAt(),
					x.getFollowUpNote(), x.getFollowUpRemindMinutes(), x.getFollowUpDoneAt());
		}).toList();
	}

	private static BigDecimal toBig(Object o) {
		return o instanceof BigDecimal b ? b : o == null ? BigDecimal.ZERO : new BigDecimal(o.toString());
	}
}
