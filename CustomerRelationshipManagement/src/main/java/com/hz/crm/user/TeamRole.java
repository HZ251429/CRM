package com.hz.crm.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A tenant-defined job role (e.g. "Sales Manager") that can be assigned to team members. */
@Entity
@Table(name = "team_roles")
public class TeamRole {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long tenantId;

	@Column(nullable = false)
	private String name;

	public Long getId() { return id; }
	public Long getTenantId() { return tenantId; }
	public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
	public String getName() { return name; }
	public void setName(String name) { this.name = name; }
}
