package com.hz.crm.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.hz.crm.tenant.TenantRepository;
import com.hz.crm.user.AppUser;
import com.hz.crm.user.LoginIdentifiers;

@Service
public class CrmUserDetailsService implements UserDetailsService {

	private final LoginIdentifiers identifiers;
	private final TenantRepository tenants;

	public CrmUserDetailsService(LoginIdentifiers identifiers, TenantRepository tenants) {
		this.identifiers = identifiers;
		this.tenants = tenants;
	}

	/** Accepts a username, an email address or a mobile number. */
	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		AppUser u = identifiers.find(username)
				.orElseThrow(() -> new UsernameNotFoundException("User not found"));
		boolean tenantActive = u.getTenantId() == null
				|| tenants.findById(u.getTenantId()).map(t -> t.isActive()).orElse(false);
		return new CrmUserDetails(u.getId(), u.getUsername(), u.getPasswordHash(), u.isActive() && tenantActive,
				u.getTenantId(), u.getRole(), u.getFullName());
	}
}
