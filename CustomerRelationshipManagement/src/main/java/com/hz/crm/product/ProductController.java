package com.hz.crm.product;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.hz.crm.security.AccessService;
import com.hz.crm.security.AccessService.Scope;
import com.hz.crm.user.Role;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Products of the current tenant. Everyone in the tenant can read them; only the tenant admin can change them. */
@RestController
@RequestMapping("/api/products")
public class ProductController {

	public record ProductRequest(@NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 20) String unit,
			@Size(max = 500) String description, Boolean active) {
	}

	private final ProductRepository products;
	private final AccessService access;

	public ProductController(ProductRepository products, AccessService access) {
		this.products = products;
		this.access = access;
	}

	private Scope adminScope() {
		Scope s = access.scope();
		if (s.me().getRole() != Role.TENANT_ADMIN) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the admin can manage products");
		}
		return s;
	}

	private Product find(Long id, Scope s) {
		return products.findById(id).filter(p -> s.tenantId().equals(p.getTenantId()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
	}

	private static String blankToNull(String v) {
		return v == null || v.isBlank() ? null : v.trim();
	}

	@GetMapping
	public List<Product> list() {
		return products.findByTenantIdOrderByNameAsc(access.scope().tenantId());
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Product create(@Valid @RequestBody ProductRequest req) {
		Scope s = adminScope();
		String name = req.name().trim();
		if (products.existsByTenantIdAndNameIgnoreCase(s.tenantId(), name)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "A product with this name already exists");
		}
		Product p = new Product();
		p.setTenantId(s.tenantId());
		p.setName(name);
		p.setUnit(req.unit().trim());
		p.setDescription(blankToNull(req.description()));
		return products.save(p);
	}

	@PutMapping("/{id}")
	public Product update(@PathVariable Long id, @Valid @RequestBody ProductRequest req) {
		Scope s = adminScope();
		Product p = find(id, s);
		String name = req.name().trim();
		if (!p.getName().equalsIgnoreCase(name) && products.existsByTenantIdAndNameIgnoreCase(s.tenantId(), name)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "A product with this name already exists");
		}
		p.setName(name);
		p.setUnit(req.unit().trim());
		p.setDescription(blankToNull(req.description()));
		if (req.active() != null) {
			p.setActive(req.active());
		}
		return products.save(p);
	}
}
