package com.hz.crm.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_users", indexes = @Index(name = "ix_app_users_tenant", columnList = "tenantId"))
public class AppUser {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(unique = true, nullable = false)
	private String username;

	@Column(nullable = false)
	private String passwordHash;

	private String fullName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Role role;

	/** null for the super admin */
	private Long tenantId;

	/** the lead this user reports to; null = reports directly to the tenant admin */
	private Long managerId;

	/** optional tenant-defined job role (see TeamRole) */
	private Long teamRoleId;

	private boolean active = true;

	public Long getId() { return id; }
	public String getUsername() { return username; }
	public void setUsername(String username) { this.username = username; }
	public String getPasswordHash() { return passwordHash; }
	public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
	public String getFullName() { return fullName; }
	public void setFullName(String fullName) { this.fullName = fullName; }
	public Role getRole() { return role; }
	public void setRole(Role role) { this.role = role; }
	public Long getTenantId() { return tenantId; }
	public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
	public Long getManagerId() { return managerId; }
	public void setManagerId(Long managerId) { this.managerId = managerId; }
	public Long getTeamRoleId() { return teamRoleId; }
	public void setTeamRoleId(Long teamRoleId) { this.teamRoleId = teamRoleId; }
	public boolean isActive() { return active; }
	public void setActive(boolean active) { this.active = active; }
}
