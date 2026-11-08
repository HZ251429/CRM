package com.hz.crm.task;

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
 * A sales target: sell {@code units} of a product. A leader assigns it to someone below them; that person can split
 * it into sub-tasks (children, {@code parentId}) for their own team, never handing out more units than they received.
 * Progress is calculated from the units sold in activities by the assignee and everyone below them since the task
 * was created.
 */
@Entity
@Table(name = "sales_tasks", indexes = { @Index(name = "ix_sales_tasks_tenant", columnList = "tenantId"), @Index(name = "ix_sales_tasks_parent", columnList = "parentId") })
public class SalesTask {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long tenantId;

	/** the task this one was split from; null for a task created from scratch */
	private Long parentId;

	@Column(nullable = false)
	private Long productId;

	@Column(nullable = false)
	private int units;

	@Column(length = 200)
	private String title;

	@Column(nullable = false)
	private Long assignedToId;

	@Column(nullable = false)
	private Long assignedById;

	@Column(nullable = false)
	private LocalDateTime createdAt;

	/** true when the task's units were taken from stock (tasks the admin creates); null for tasks made before stock */
	private Boolean fromStock;

	public boolean isFromStock() { return Boolean.TRUE.equals(fromStock); }
	public void setFromStock(boolean fromStock) { this.fromStock = fromStock; }

	@PrePersist
	void onCreate() {
		createdAt = LocalDateTime.now();
	}

	public Long getId() { return id; }
	public Long getTenantId() { return tenantId; }
	public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
	public Long getParentId() { return parentId; }
	public void setParentId(Long parentId) { this.parentId = parentId; }
	public Long getProductId() { return productId; }
	public void setProductId(Long productId) { this.productId = productId; }
	public int getUnits() { return units; }
	public void setUnits(int units) { this.units = units; }
	public String getTitle() { return title; }
	public void setTitle(String title) { this.title = title; }
	public Long getAssignedToId() { return assignedToId; }
	public void setAssignedToId(Long assignedToId) { this.assignedToId = assignedToId; }
	public Long getAssignedById() { return assignedById; }
	public void setAssignedById(Long assignedById) { this.assignedById = assignedById; }
	public LocalDateTime getCreatedAt() { return createdAt; }
}
