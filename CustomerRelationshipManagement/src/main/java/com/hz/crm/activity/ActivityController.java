package com.hz.crm.activity;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import com.hz.crm.customer.CustomerRepository;
import com.hz.crm.notification.Notification;
import com.hz.crm.notification.NotificationService;
import com.hz.crm.task.SalesTask;
import com.hz.crm.task.SalesTaskRepository;
import com.hz.crm.task.TaskProgressService;
import com.hz.crm.user.Role;
import java.util.Map;
import com.hz.crm.product.ProductRepository;
import com.hz.crm.security.AccessService;
import com.hz.crm.security.AccessService.Scope;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/activities")
public class ActivityController {

	private final ActivityRepository repository;
	private final CustomerRepository customers;
	private final AccessService access;
	private final ProductRepository products;
	private final NotificationService notifications;
	private final SalesTaskRepository tasks;
	private final TaskProgressService progress;
	private final ActivitySearch search;

	public ActivityController(ActivityRepository repository, CustomerRepository customers, AccessService access,
			ProductRepository products, NotificationService notifications, SalesTaskRepository tasks,
			TaskProgressService progress, ActivitySearch search) {
		this.search = search;
		this.tasks = tasks;
		this.progress = progress;
		this.notifications = notifications;
		this.products = products;
		this.repository = repository;
		this.customers = customers;
		this.access = access;
	}

	private Activity visible(Long id, Scope s) {
		return repository.findById(id).filter(a -> s.owns(a.getTenantId(), a.getOwnerId())).orElse(null);
	}

	private void checkCustomer(Long customerId, Scope s) {
		boolean ok = customers.findById(customerId).filter(c -> s.owns(c.getTenantId(), c.getOwnerId())).isPresent();
		if (!ok) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown customer");
		}
	}

	/**
	 * A sale needs both a product of this tenant and a number of units. Inactive products cannot be used for new
	 * sales, but an existing entry may keep the product it already has.
	 */
	private void checkSale(Activity activity, Scope s, Long currentProductId) {
		if (activity.getProductId() == null) {
			activity.setUnitsSold(null);
			activity.setSaleAmount(null);
			activity.setAmountPaid(null);
			return;
		}
		if (activity.getUnitsSold() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the number of units sold");
		}
		if (activity.getSaleAmount() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the total amount of the sale");
		}
		if (activity.getAmountPaid() == null) {
			activity.setAmountPaid(activity.getSaleAmount()); // not stated = fully paid
		}
		if (activity.getAmountPaid().compareTo(activity.getSaleAmount()) > 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount paid cannot be more than the total amount");
		}
		var product = products.findById(activity.getProductId()).filter(p -> s.tenantId().equals(p.getTenantId()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown product"));
		if (!product.isActive() && !product.getId().equals(currentProductId)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This product is inactive");
		}
		checkBalance(activity, s, product);
	}

	/**
	 * A member may only sell what is left in their own balance for the product: the units on their tasks for it that
	 * are not split to their team and not sold yet. When editing a sale, that sale's own units are given back first.
	 * The admin has no tasks of their own and is not limited.
	 */
	private void checkBalance(Activity activity, Scope s, com.hz.crm.product.Product product) {
		if (s.me().getRole() != Role.MEMBER) {
			return;
		}
		Long me = s.me().getId();
		List<SalesTask> all = tasks.findByTenantId(s.tenantId());
		List<SalesTask> mine = all.stream()
				.filter(t -> me.equals(t.getAssignedToId()) && product.getId().equals(t.getProductId())).toList();
		long available = 0;
		if (!mine.isEmpty()) {
			// a member's tasks only count sales by themselves and their team
			List<Activity> otherSales = repository.findSales(s.tenantId(), s.ownerIds()).stream()
					.filter(a -> activity.getId() == null || !activity.getId().equals(a.getId())).toList();
			List<SalesTask> related = progress.related(all, mine);
			Map<Long, Long> own = progress.ownBalance(related, progress.soldByTask(s, related, otherSales));
			available = mine.stream().mapToLong(t -> own.getOrDefault(t.getId(), 0L)).sum();
		}
		if (activity.getUnitsSold() > available) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, mine.isEmpty()
					? "You have no units of " + product.getName() + " to sell. Ask your lead to assign you a task for it."
					: "You can sell at most " + available + " " + product.getUnit() + " of " + product.getName()
							+ ". That is all that is left in your balance.");
		}
	}

	/** One customer's activities (the full history of a large team is only available page by page, see /page). */
	@GetMapping
	public List<Activity> list(@RequestParam Long customerId) {
		Scope s = access.scope();
		return repository.findByTenantIdAndOwnerIdInAndCustomerId(s.tenantId(), s.ownerIds(), customerId);
	}

	/**
	 * The activity log, one page at a time, newest first, with totals for everything that matches. The search
	 * text matches title, attendees, notes, outcome, the customer, the member's name and the product name.
	 */
	@GetMapping("/page")
	public ActivitySearch.Result page(@RequestParam(defaultValue = "") String q,
			@RequestParam(required = false) String type, @RequestParam(required = false) Long ownerId,
			@RequestParam(defaultValue = "false") boolean salesOnly, @RequestParam(defaultValue = "false") boolean creditOnly,
			@RequestParam(required = false) java.time.LocalDate from, @RequestParam(required = false) java.time.LocalDate to,
			@RequestParam(required = false) Long customerId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
		Scope s = access.scope();
		java.util.Collection<Long> owners = ownerId == null ? s.ownerIds()
				: s.canSeeOwner(ownerId) ? java.util.Set.of(ownerId) : java.util.Set.of(-1L);
		String text = q.trim().toLowerCase();
		List<Long> qOwners = text.isEmpty() ? List.of() : s.tenantUsers().stream()
				.filter(u -> s.canSeeOwner(u.getId()) && u.getFullName() != null && u.getFullName().toLowerCase().contains(text))
				.map(com.hz.crm.user.AppUser::getId).toList();
		List<Long> qProducts = text.isEmpty() ? List.of() : products.findByTenantIdOrderByNameAsc(s.tenantId()).stream()
				.filter(p -> p.getName().toLowerCase().contains(text)).map(com.hz.crm.product.Product::getId).toList();
		return search.search(new ActivitySearch.Filter(s.tenantId(), owners, text, qOwners, qProducts, type, salesOnly,
				creditOnly, from, to, customerId), Math.max(page, 0), Math.min(Math.max(size, 1), 100));
	}

	/** One activity with its customer's name. */
	@GetMapping("/{id}")
	public ResponseEntity<ActivitySearch.Row> get(@PathVariable Long id) {
		Activity a = visible(id, access.scope());
		return a == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(search.rows(List.of(a)).get(0));
	}

	/** Several activities by id with customer names (at most 200), e.g. the deals behind a dashboard figure. */
	@GetMapping("/rows")
	public List<ActivitySearch.Row> rows(@RequestParam List<Long> ids) {
		Scope s = access.scope();
		List<Long> wanted = ids.stream().distinct().limit(200).toList();
		return search.rows(repository.findAllById(wanted).stream().filter(a -> s.owns(a.getTenantId(), a.getOwnerId()))
				.sorted(java.util.Comparator.comparing(Activity::getActivityTime,
						java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
				.toList());
	}

	/** A sale in short form, for the dashboard chart and figures. */
	public record Sale(Long id, Long ownerId, Long productId, java.time.LocalDateTime activityTime, Integer unitsSold,
			java.math.BigDecimal saleAmount, java.math.BigDecimal creditAmount) {
	}

	/** All sales the user may see, in short form (no notes, no customer details). */
	@GetMapping("/sales")
	public List<Sale> sales() {
		Scope s = access.scope();
		return repository.findSales(s.tenantId(), s.ownerIds()).stream()
				.map(a -> new Sale(a.getId(), a.getOwnerId(), a.getProductId(), a.getActivityTime(), a.getUnitsSold(),
						a.getSaleAmount(), a.getCreditAmount()))
				.toList();
	}

	/** Per customer: activities, last contact, sales and credit. For the customers on one page of the list. */
	@GetMapping("/stats")
	public Map<Long, Map<String, Object>> stats(@RequestParam List<Long> customerIds) {
		Scope s = access.scope();
		Map<Long, Map<String, Object>> out = new java.util.LinkedHashMap<>();
		List<Long> ids = customerIds.stream().distinct().limit(200).toList();
		if (ids.isEmpty()) {
			return out;
		}
		for (Object[] r : repository.statsByCustomer(s.tenantId(), s.ownerIds(), ids)) {
			Map<String, Object> m = new java.util.LinkedHashMap<>();
			m.put("count", r[1]);
			m.put("last", r[2]);
			m.put("deals", r[3]);
			m.put("sales", r[4]);
			m.put("credit", r[5]);
			out.put((Long) r[0], m);
		}
		return out;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Activity create(@Valid @RequestBody Activity activity) {
		Scope s = access.scope();
		checkCustomer(activity.getCustomerId(), s);
		checkSale(activity, s, null);
		activity.setId(null);
		if (activity.getActivityTime() == null) {
			activity.setActivityTime(java.time.LocalDateTime.now());
		}
		activity.setTenantId(s.tenantId());
		activity.setOwnerId(s.me().getId());
		Activity saved = repository.save(activity);
		notifyLeads(saved, s);
		return saved;
	}

	private static final java.text.NumberFormat RUPEES = rupees();

	private static java.text.NumberFormat rupees() {
		java.text.NumberFormat f = java.text.NumberFormat.getCurrencyInstance(java.util.Locale.of("en", "IN"));
		f.setMaximumFractionDigits(0);
		return f;
	}

	/** e.g. "Rep One logged a visit with Big Buyer: Order #3 · sale of 10 pcs Purifier, ₹45,000" */
	private void notifyLeads(Activity a, Scope s) {
		String customer = customers.findById(a.getCustomerId()).map(c -> c.getFirstName() + " " + c.getLastName()).orElse("a customer");
		String type = a.getType() == null ? "activity" : a.getType().toLowerCase(java.util.Locale.ROOT);
		StringBuilder msg = new StringBuilder(s.me().getFullName()).append(" logged a ").append(type)
				.append(" with ").append(customer).append(": ").append(a.getSubject());
		if (a.getProductId() != null) {
			products.findById(a.getProductId()).ifPresent(p -> {
				msg.append(" · sale of ").append(a.getUnitsSold()).append(' ').append(p.getUnit()).append(' ').append(p.getName());
				if (a.getSaleAmount() != null) {
					synchronized (RUPEES) {
						msg.append(", ").append(RUPEES.format(a.getSaleAmount()));
					}
				}
			});
		}
		notifications.notifyLeads(s.tenantId(), s.me().getId(), Notification.Type.ACTIVITY_ADDED, msg.toString(), a.getId());
	}

	@PutMapping("/{id}")
	public ResponseEntity<Activity> update(@PathVariable Long id, @Valid @RequestBody Activity activity) {
		Scope s = access.scope();
		Activity a = visible(id, s);
		if (a == null) {
			return ResponseEntity.notFound().build();
		}
		checkCustomer(activity.getCustomerId(), s);
		activity.setId(a.getId()); // so the balance check gives this sale's own units back
		checkSale(activity, s, a.getProductId());
		a.setCustomerId(activity.getCustomerId());
		a.setProductId(activity.getProductId());
		a.setUnitsSold(activity.getUnitsSold());
		a.setSaleAmount(activity.getSaleAmount());
		a.setAmountPaid(activity.getAmountPaid());
		a.setType(activity.getType());
		a.setSubject(activity.getSubject());
		a.setAttendees(activity.getAttendees());
		a.setNotes(activity.getNotes());
		a.setOutcome(activity.getOutcome());
		if (activity.getActivityTime() != null) {
			a.setActivityTime(activity.getActivityTime());
		}
		return ResponseEntity.ok(repository.save(a));
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		if (visible(id, access.scope()) == null) {
			return ResponseEntity.notFound().build();
		}
		repository.deleteById(id);
		return ResponseEntity.noContent().build();
	}
}