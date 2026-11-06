package com.hz.crm.customer;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hz.crm.activity.ActivityRepository;
import com.hz.crm.common.PageResult;
import com.hz.crm.deal.DealRepository;
import com.hz.crm.notification.Notification;
import com.hz.crm.notification.NotificationService;
import com.hz.crm.security.AccessService;
import com.hz.crm.security.AccessService.Scope;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

	private final CustomerRepository repository;
	private final DealRepository dealRepository;
	private final ActivityRepository activityRepository;
	private final AccessService access;

	private final NotificationService notifications;

	public CustomerController(CustomerRepository repository, DealRepository dealRepository,
			ActivityRepository activityRepository, AccessService access, NotificationService notifications) {
		this.notifications = notifications;
		this.repository = repository;
		this.dealRepository = dealRepository;
		this.activityRepository = activityRepository;
		this.access = access;
	}

	private Customer visible(Long id, Scope s) {
		return repository.findById(id).filter(c -> s.owns(c.getTenantId(), c.getOwnerId())).orElse(null);
	}

	@GetMapping
	public PageResult<Customer> list(@RequestParam(defaultValue = "") String q,
			@RequestParam(required = false) Long ownerId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
		Scope s = access.scope();
		java.util.Collection<Long> owners = s.ownerIds();
		if (ownerId != null) {
			// filter by one owner, but never beyond what the current user may see
			owners = s.canSeeOwner(ownerId) ? java.util.Set.of(ownerId) : java.util.Set.of(-1L);
		}
		return PageResult.of(repository.search(s.tenantId(), owners, q.trim(),
				PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by("id").descending())));
	}

	@GetMapping("/all")
	public List<Customer> all() {
		Scope s = access.scope();
		return repository.findByTenantIdAndOwnerIdIn(s.tenantId(), s.ownerIds(), Sort.by("firstName"));
	}

	@GetMapping("/{id}")
	public ResponseEntity<Customer> get(@PathVariable Long id) {
		Customer c = visible(id, access.scope());
		return c == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(c);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Customer create(@Valid @RequestBody Customer customer) {
		Scope s = access.scope();
		customer.setId(null);
		customer.setTenantId(s.tenantId());
		customer.setOwnerId(s.me().getId());
		Customer saved = repository.save(customer);
		notifications.notifyLeads(s.tenantId(), s.me().getId(), Notification.Type.CUSTOMER_ADDED,
				s.me().getFullName() + " added a new customer: " + saved.getFirstName() + " " + saved.getLastName()
						+ (saved.getCompany() == null || saved.getCompany().isBlank() ? "" : " (" + saved.getCompany() + ")"),
				saved.getId());
		return saved;
	}

	@PutMapping("/{id}")
	public ResponseEntity<Customer> update(@PathVariable Long id, @Valid @RequestBody Customer customer) {
		Customer existing = visible(id, access.scope());
		if (existing == null) {
			return ResponseEntity.notFound().build();
		}
		existing.setFirstName(customer.getFirstName());
		existing.setLastName(customer.getLastName());
		existing.setEmail(customer.getEmail());
		existing.setPhone(customer.getPhone());
		existing.setCompany(customer.getCompany());
		return ResponseEntity.ok(repository.save(existing));
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		if (visible(id, access.scope()) == null) {
			return ResponseEntity.notFound().build();
		}
		dealRepository.deleteByCustomerId(id);
		activityRepository.deleteByCustomerId(id);
		repository.deleteById(id);
		return ResponseEntity.noContent().build();
	}
}