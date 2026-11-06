package com.hz.crm.security;

import java.util.List;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import com.hz.crm.user.Role;

public class CrmUserDetails extends User {

	private static final long serialVersionUID = 1L;

	private final Long id;
	private final Long tenantId;
	private final Role role;
	private final String fullName;
	/**
	 * The password hash at login time. Kept (unlike the erasable password) so that a later password change can be
	 * detected and older sessions ended. It is a hash and only lives in the server-side session.
	 */
	private final String passwordStamp;

	public CrmUserDetails(Long id, String username, String passwordHash, boolean enabled, Long tenantId, Role role,
			String fullName) {
		super(username, passwordHash, enabled, true, true, true,
				List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
		this.id = id;
		this.tenantId = tenantId;
		this.role = role;
		this.fullName = fullName;
		this.passwordStamp = passwordHash;
	}

	public String getPasswordStamp() { return passwordStamp; }
	public Long getId() { return id; }
	public Long getTenantId() { return tenantId; }
	public Role getRole() { return role; }
	public String getFullName() { return fullName; }
}
