package com.hz.crm.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A product a tenant sells. Units of it are assigned in tasks and recorded as sold in activities. */
@Entity
@Table(name = "products")
public class Product {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long tenantId;

	@Column(nullable = false, length = 100)
	private String name;

	/** what one unit is called, e.g. "pcs", "boxes", "licences" */
	@Column(nullable = false, length = 20)
	private String unit = "units";

	@Column(length = 500)
	private String description;

	/** inactive products can no longer be picked for new tasks or sales, but old records keep them */
	private boolean active = true;

	public Long getId() { return id; }
	public Long getTenantId() { return tenantId; }
	public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
	public String getName() { return name; }
	public void setName(String name) { this.name = name; }
	public String getUnit() { return unit; }
	public void setUnit(String unit) { this.unit = unit; }
	public String getDescription() { return description; }
	public void setDescription(String description) { this.description = description; }
	public boolean isActive() { return active; }
	public void setActive(boolean active) { this.active = active; }
}
