package com.hz.crm.deal;

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
@RequestMapping("/api/deals")
public class DealController {

	private final DealRepository repository;
	private final CustomerRepository customers;
	private final AccessService access;

	public DealController(DealRepository repository, CustomerRepository customers, AccessService access) {
		this.repository = repository;
		this.customers = customers;
		this.access = access;
	}

	private Deal visible(Long id, Scope s) {
		return repository.findById(id).filter(d -> s.owns(d.getTenantId(), d.getOwnerId())).orElse(null);
	}

	private void checkCustomer(Long customerId, Scope s) {
		boolean ok = customers.findById(customerId).filter(c -> s.owns(c.getTenantId(), c.getOwnerId())).isPresent();
		if (!ok) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown customer");
		}
	}

	@GetMapping
	public List<Deal> list(@RequestParam(required = false) Long customerId) {
		Scope s = access.scope();
		return customerId == null ? repository.findByTenantIdAndOwnerIdIn(s.tenantId(), s.ownerIds())
				: repository.findByTenantIdAndOwnerIdInAndCustomerId(s.tenantId(), s.ownerIds(), customerId);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Deal create(@Valid @RequestBody Deal deal) {
		Scope s = access.scope();
		checkCustomer(deal.getCustomerId(), s);
		deal.setId(null);
		deal.setTenantId(s.tenantId());
		deal.setOwnerId(s.me().getId());
		return repository.save(deal);
	}

	@PutMapping("/{id}")
	public ResponseEntity<Deal> update(@PathVariable Long id, @Valid @RequestBody Deal deal) {
		Scope s = access.scope();
		Deal d = visible(id, s);
		if (d == null) {
			return ResponseEntity.notFound().build();
		}
		checkCustomer(deal.getCustomerId(), s);
		d.setCustomerId(deal.getCustomerId());
		d.setTitle(deal.getTitle());
		d.setAmount(deal.getAmount());
		d.setStage(deal.getStage());
		d.setExpectedCloseDate(deal.getExpectedCloseDate());
		return ResponseEntity.ok(repository.save(d));
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