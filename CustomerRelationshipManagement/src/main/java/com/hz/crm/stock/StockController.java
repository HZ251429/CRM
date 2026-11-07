package com.hz.crm.stock;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.hz.crm.product.Product;
import com.hz.crm.product.ProductRepository;
import com.hz.crm.security.AccessService;
import com.hz.crm.security.AccessService.Scope;
import com.hz.crm.stock.StockService.Level;
import com.hz.crm.user.AppUser;
import com.hz.crm.user.Role;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Admin only: stock of each product. Tasks the admin creates cannot assign more units than are left in stock. */
@RestController
@RequestMapping("/api/stock")
public class StockController {

	public record StockRow(Long productId, String productName, String unit, boolean active, long total, long assigned,
			long left, long sold, LocalDateTime lastChangedAt) {
	}

	public record EntryView(Long id, int quantity, String note, String createdByName, LocalDateTime createdAt) {
	}

	/** quantity > 0 adds stock, quantity < 0 removes it */
	public record EntryRequest(@NotNull Long productId, @NotNull @Min(-1_000_000) @Max(1_000_000) Integer quantity,
			@Size(max = 200) String note) {
	}

	private final StockEntryRepository entries;
	private final StockService stock;
	private final ProductRepository products;
	private final AccessService access;

	public StockController(StockEntryRepository entries, StockService stock, ProductRepository products,
			AccessService access) {
		this.entries = entries;
		this.stock = stock;
		this.products = products;
		this.access = access;
	}

	private Scope adminScope() {
		Scope s = access.scope();
		if (s.me().getRole() != Role.TENANT_ADMIN) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the admin can manage stock");
		}
		return s;
	}

	private Product product(Long id, Scope s) {
		return products.findById(id).filter(p -> s.tenantId().equals(p.getTenantId()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
	}

	@GetMapping
	public List<StockRow> list() {
		Scope s = adminScope();
		Map<Long, Level> levels = stock.levels(s.tenantId());
		Map<Long, LocalDateTime> last = entries.findByTenantId(s.tenantId()).stream()
				.collect(Collectors.toMap(StockEntry::getProductId, StockEntry::getCreatedAt, (a, b) -> a.isAfter(b) ? a : b));
		return products.findByTenantIdOrderByNameAsc(s.tenantId()).stream().map(p -> {
			Level l = levels.getOrDefault(p.getId(), new Level(0, 0, 0, 0));
			return new StockRow(p.getId(), p.getName(), p.getUnit(), p.isActive(), l.total(), l.assigned(), l.left(),
					l.sold(), last.get(p.getId()));
		}).toList();
	}

	@GetMapping("/{productId}/entries")
	public List<EntryView> history(@PathVariable Long productId) {
		Scope s = adminScope();
		product(productId, s);
		Map<Long, String> names = s.tenantUsers().stream().collect(Collectors.toMap(AppUser::getId,
				u -> u.getFullName() != null ? u.getFullName() : u.getUsername()));
		return entries.findByTenantIdAndProductIdOrderByCreatedAtDesc(s.tenantId(), productId).stream()
				.map(e -> new EntryView(e.getId(), e.getQuantity(), e.getNote(),
						names.getOrDefault(e.getCreatedById(), "Unknown"), e.getCreatedAt()))
				.toList();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public EntryView add(@Valid @RequestBody EntryRequest req) {
		Scope s = adminScope();
		Product p = product(req.productId(), s);
		if (req.quantity() == 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the number of units");
		}
		if (req.quantity() < 0) {
			Level l = stock.level(s.tenantId(), p.getId());
			if (-req.quantity() > l.left()) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only " + Math.max(0, l.left()) + " "
						+ p.getUnit() + " are free to remove; the rest is already assigned in tasks");
			}
		}
		StockEntry e = new StockEntry();
		e.setTenantId(s.tenantId());
		e.setProductId(p.getId());
		e.setQuantity(req.quantity());
		e.setNote(req.note() == null || req.note().isBlank() ? null : req.note().trim());
		e.setCreatedById(s.me().getId());
		e = entries.save(e);
		return new EntryView(e.getId(), e.getQuantity(), e.getNote(), s.me().getFullName(), e.getCreatedAt());
	}

	/** Undo a stock entry (e.g. a typing mistake), as long as tasks still fit in what is left. */
	@DeleteMapping("/entries/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		Scope s = adminScope();
		StockEntry e = entries.findById(id).filter(x -> s.tenantId().equals(x.getTenantId()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		if (e.getQuantity() > 0 && e.getQuantity() > stock.level(s.tenantId(), e.getProductId()).left()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"This stock is already assigned in tasks, so the entry cannot be removed");
		}
		entries.delete(e);
		return ResponseEntity.noContent().build();
	}
}
