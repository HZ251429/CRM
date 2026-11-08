package com.hz.crm.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** A tenant-defined job role (e.g. "Sales Manager") that can be assigned to team members. */
@Entity
@Table(name = "team_roles", indexes = @Index(name = "ix_team_roles_tenant", columnList = "tenantId"))
public class TeamRole {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long tenantId;

	@Column(nullable = false)
	private String name;

	/** who introduced the role; null = company-wide (made by the admin before roles had an owner) */
	private Long createdById;

	public Long getId() { return id; }
	public Long getCreatedById() { return createdById; }
	public void setCreatedById(Long createdById) { this.createdById = createdById; }
	public Long getTenantId() { return tenantId; }
	public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
	public String getName() { return name; }
	public void setName(String name) { this.name = name; }
}
