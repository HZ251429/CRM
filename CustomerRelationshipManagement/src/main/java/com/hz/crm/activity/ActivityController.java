package com.hz.crm.activity;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import com.hz.crm.customer.CustomerRepository;
import com.hz.crm.security.AccessService;
import com.hz.crm.security.AccessService.Scope;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/activities")
public class ActivityController {

	private final ActivityRepository repository;
	private final CustomerRepository customers;
	private final AccessService access;

	public ActivityController(ActivityRepository repository, CustomerRepository customers, AccessService access) {
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

	@GetMapping
	public List<Activity> list(@RequestParam(required = false) Long customerId) {
		Scope s = access.scope();
		return customerId == null ? repository.findByTenantIdAndOwnerIdIn(s.tenantId(), s.ownerIds())
				: repository.findByTenantIdAndOwnerIdInAndCustomerId(s.tenantId(), s.ownerIds(), customerId);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Activity create(@Valid @RequestBody Activity activity) {
		Scope s = access.scope();
		checkCustomer(activity.getCustomerId(), s);
		activity.setId(null);
		if (activity.getActivityTime() == null) {
			activity.setActivityTime(java.time.LocalDateTime.now());
		}
		activity.setTenantId(s.tenantId());
		activity.setOwnerId(s.me().getId());
		return repository.save(activity);
	}

	@PutMapping("/{id}")
	public ResponseEntity<Activity> update(@PathVariable Long id, @Valid @RequestBody Activity activity) {
		Scope s = access.scope();
		Activity a = visible(id, s);
		if (a == null) {
			return ResponseEntity.notFound().build();
		}
		checkCustomer(activity.getCustomerId(), s);
		a.setCustomerId(activity.getCustomerId());
		a.setType(activity.getType());
		a.setSubject(activity.getSubject());
		a.setNotes(activity.getNotes());
		a.setActivityTime(activity.getActivityTime());
		a.setCompleted(activity.isCompleted());
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