package com.hz.crm.stock;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * One change to a product's stock, made by the admin: units received (positive) or removed (negative, e.g. damaged
 * or returned goods). A product's total stock is the sum of its entries.
 */
@Entity
@Table(name = "stock_entries", indexes = @Index(name = "ix_stock_entries_tenant_product", columnList = "tenantId, productId"))
public class StockEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long tenantId;

	@Column(nullable = false)
	private Long productId;

	@Column(nullable = false)
	private int quantity;

	@Column(length = 200)
	private String note;

	@Column(nullable = false)
	private Long createdById;

	@Column(nullable = false)
	private LocalDateTime createdAt;

	@PrePersist
	void onCreate() {
		createdAt = LocalDateTime.now();
	}

	public Long getId() { return id; }
	public Long getTenantId() { return tenantId; }
	public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
	public Long getProductId() { return productId; }
	public void setProductId(Long productId) { this.productId = productId; }
	public int getQuantity() { return quantity; }
	public void setQuantity(int quantity) { this.quantity = quantity; }
	public String getNote() { return note; }
	public void setNote(String note) { this.note = note; }
	public Long getCreatedById() { return createdById; }
	public void setCreatedById(Long createdById) { this.createdById = createdById; }
	public LocalDateTime getCreatedAt() { return createdAt; }
}
