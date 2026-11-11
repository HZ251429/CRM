package com.hz.crm.enquiry;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
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

import com.hz.crm.activity.ActivityRepository;
import com.hz.crm.customer.Customer;
import com.hz.crm.customer.CustomerRepository;
import com.hz.crm.notification.Notification;
import com.hz.crm.notification.NotificationService;
import com.hz.crm.product.Product;
import com.hz.crm.product.ProductRepository;
import com.hz.crm.security.AccessService;
import com.hz.crm.security.AccessService.Scope;
import com.hz.crm.user.AppUser;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Enquiries from new customers. Whoever takes the enquiry enters the customer's details and hands it to themselves or
 * someone below them; the customer goes straight into that person's customer list, so they can log activities and
 * record sales against it. Everyone sees the enquiries handled by them or anyone below them, and those they gave out.
 */
@RestController
@RequestMapping("/api/enquiries")
public class EnquiryController {

	/** NEW (nothing logged yet), CONTACTED (an activity), WON (a sale), LOST (closed by hand) */
	public record EnquiryView(Long id, Long customerId, String customerName, String phone, String email, String address,
			String company, String requirement, Long productId, String productName, Long assignedToId,
			String assignedToName, Long assignedById, String assignedByName, LocalDateTime createdAt, String status,
			long activities, long sales, BigDecimal salesAmount, LocalDateTime lastActivityAt) {
	}

	public record NewCustomer(@Size(max = 250) String name, @Size(max = 25) String phone, @Size(max = 254) String email,
			@Size(max = 500) String address, @Size(max = 150) String company) {
	}

	public record CreateRequest(@NotNull @Valid NewCustomer customer, @Size(max = 1000) String requirement,
			Long productId, @NotNull Long assigneeId) {
	}

	public record StatusRequest(@NotNull String status) {
	}

	private final EnquiryRepository enquiries;
	private final CustomerRepository customers;
	private final ActivityRepository activities;
	private final ProductRepository products;
	private final AccessService access;
	private final NotificationService notifications;
	private final Validator validator;

	public EnquiryController(EnquiryRepository enquiries, CustomerRepository customers, ActivityRepository activities,
			ProductRepository products, AccessService access, NotificationService notifications, Validator validator) {
		this.enquiries = enquiries;
		this.customers = customers;
		this.activities = activities;
		this.products = products;
		this.access = access;
		this.notifications = notifications;
		this.validator = validator;
	}

	private static boolean visible(Enquiry e, Scope s) {
		return s.tenantId().equals(e.getTenantId())
				&& (s.canSeeOwner(e.getAssignedToId()) || s.me().getId().equals(e.getAssignedById()));
	}

	private static String name(AppUser u) {
		return u == null ? "Unknown" : u.getFullName() != null ? u.getFullName() : u.getUsername();
	}

	@GetMapping
	public List<EnquiryView> list() {
		Scope s = access.scope();
		List<Enquiry> mine = enquiries.findByTenantIdOrderByCreatedAtDesc(s.tenantId()).stream()
				.filter(e -> visible(e, s)).toList();
		return views(s, mine);
	}

	private List<EnquiryView> views(Scope s, List<Enquiry> list) {
		if (list.isEmpty()) {
			return List.of();
		}
		Map<Long, AppUser> users = s.tenantUsers().stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));
		Map<Long, Customer> byId = customers.findAllById(list.stream().map(Enquiry::getCustomerId).toList()).stream()
				.collect(Collectors.toMap(Customer::getId, Function.identity()));
		Map<Long, String> productNames = products.findByTenantIdOrderByNameAsc(s.tenantId()).stream()
				.collect(Collectors.toMap(Product::getId, Product::getName));
		// progress from the customer's activities and sales (all of them, whoever logged them)
		Map<Long, Object[]> stats = new HashMap<>();
		List<Long> owners = s.tenantUsers().stream().map(AppUser::getId).toList();
		for (Object[] r : activities.statsByCustomer(s.tenantId(), owners, byId.keySet())) {
			stats.put((Long) r[0], r);
		}
		return list.stream().filter(e -> byId.containsKey(e.getCustomerId())).map(e -> {
			Customer c = byId.get(e.getCustomerId());
			Object[] st = stats.get(c.getId());
			long count = st == null ? 0 : ((Number) st[1]).longValue();
			long deals = st == null ? 0 : ((Number) st[3]).longValue();
			BigDecimal amount = st == null || st[4] == null ? BigDecimal.ZERO : new BigDecimal(st[4].toString());
			String status = Enquiry.Status.LOST.name().equals(e.getStatus()) ? "LOST"
					: deals > 0 ? "WON" : count > 0 ? "CONTACTED" : "NEW";
			return new EnquiryView(e.getId(), c.getId(), c.getName(), c.getPhone(), c.getEmail(), c.getAddress(),
					c.getCompany(), e.getRequirement(), e.getProductId(),
					e.getProductId() == null ? null : productNames.get(e.getProductId()), e.getAssignedToId(),
					name(users.get(e.getAssignedToId())), e.getAssignedById(), name(users.get(e.getAssignedById())),
					e.getCreatedAt(), status, count - deals, deals, amount, st == null ? null : (LocalDateTime) st[2]);
		}).toList();
	}

	/** Takes an enquiry: saves the new customer for the assignee and tells them. */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Transactional
	public EnquiryView create(@Valid @RequestBody CreateRequest req) {
		Scope s = access.scope();
		Long me = s.me().getId();
		AppUser assignee = s.tenantUsers().stream().filter(u -> u.getId().equals(req.assigneeId())).findFirst()
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown team member"));
		if (!s.canSeeOwner(assignee.getId())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You can only give enquiries to yourself or people below you");
		}
		if (!assignee.isActive()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This team member is inactive");
		}
		Long productId = req.productId();
		if (productId != null && products.findById(productId).filter(p -> s.tenantId().equals(p.getTenantId())).isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown product");
		}

		Customer c = new Customer();
		c.setName(req.customer().name());
		c.setPhone(req.customer().phone());
		c.setEmail(req.customer().email());
		c.setAddress(req.customer().address());
		c.setCompany(req.customer().company());
		var problems = validator.validate(c);
		if (!problems.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problems.stream().map(ConstraintViolation::getMessage)
					.distinct().collect(Collectors.joining("; ")));
		}
		c.setTenantId(s.tenantId());
		c.setOwnerId(assignee.getId()); // the customer is in the assignee's list
		c = customers.save(c);

		Enquiry e = new Enquiry();
		e.setTenantId(s.tenantId());
		e.setCustomerId(c.getId());
		e.setAssignedToId(assignee.getId());
		e.setAssignedById(me);
		e.setRequirement(req.requirement() == null || req.requirement().isBlank() ? null : req.requirement().trim());
		e.setProductId(productId);
		e = enquiries.save(e);

		String what = e.getRequirement() == null ? "" : ": " + e.getRequirement();
		notifications.notifyUser(s.tenantId(), assignee.getId(), me, Notification.Type.ENQUIRY_ASSIGNED,
				name(s.tenantUsers().stream().filter(u -> u.getId().equals(me)).findFirst().orElse(null))
						+ " gave you a new enquiry from " + c.getName()
						+ (c.getPhone() != null ? " (" + c.getPhone() + ")" : "") + what,
				e.getId());
		return views(s, List.of(e)).get(0);
	}

	/** Close an enquiry as lost, or reopen it. Won and contacted follow from the activities and sales. */
	@PutMapping("/{id}/status")
	public EnquiryView status(@PathVariable Long id, @RequestBody StatusRequest req) {
		Scope s = access.scope();
		Enquiry e = enquiries.findById(id).filter(x -> visible(x, s))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		if (!"LOST".equals(req.status()) && !"OPEN".equals(req.status())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status must be LOST or OPEN");
		}
		e.setStatus(req.status());
		return views(s, List.of(enquiries.save(e))).get(0);
	}
}
