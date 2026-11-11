package com.hz.crm.enquiry;

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
 * An enquiry from a new customer, handed to someone in the team to follow up. Creating it adds the customer to that
 * person's customer list; they then log activities and sales against the customer as usual. Its progress (new,
 * contacted, won) follows from those activities and sales; only "lost" is set by hand.
 */
@Entity
@Table(name = "enquiries", indexes = @Index(name = "ix_enquiries_tenant", columnList = "tenantId"))
public class Enquiry {

	public enum Status { OPEN, LOST }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long tenantId;

	@Column(nullable = false)
	private Long customerId;

	/** who follows it up (the customer is theirs) */
	@Column(nullable = false)
	private Long assignedToId;

	/** who took the enquiry */
	@Column(nullable = false)
	private Long assignedById;

	/** what the customer is asking for */
	@Column(length = 1000)
	private String requirement;

	/** the product they are interested in, if known */
	private Long productId;

	@Column(nullable = false, length = 10)
	private String status = Status.OPEN.name();

	@Column(nullable = false)
	private LocalDateTime createdAt;

	@PrePersist
	void onCreate() {
		createdAt = LocalDateTime.now();
	}

	public Long getId() { return id; }
	public Long getTenantId() { return tenantId; }
	public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
	public Long getCustomerId() { return customerId; }
	public void setCustomerId(Long customerId) { this.customerId = customerId; }
	public Long getAssignedToId() { return assignedToId; }
	public void setAssignedToId(Long assignedToId) { this.assignedToId = assignedToId; }
	public Long getAssignedById() { return assignedById; }
	public void setAssignedById(Long assignedById) { this.assignedById = assignedById; }
	public String getRequirement() { return requirement; }
	public void setRequirement(String requirement) { this.requirement = requirement; }
	public Long getProductId() { return productId; }
	public void setProductId(Long productId) { this.productId = productId; }
	public String getStatus() { return status; }
	public void setStatus(String status) { this.status = status; }
	public LocalDateTime getCreatedAt() { return createdAt; }
}
