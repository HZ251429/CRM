package com.hz.crm.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.hz.crm.tenant.TenantRepository;
import com.hz.crm.user.AppUser;
import com.hz.crm.user.AppUserRepository;

@Service
public class CrmUserDetailsService implements UserDetailsService {

	private final AppUserRepository users;
	private final TenantRepository tenants;

	public CrmUserDetailsService(AppUserRepository users, TenantRepository tenants) {
		this.users = users;
		this.tenants = tenants;
	}

	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		AppUser u = users.findByUsernameIgnoreCase(username.trim())
				.orElseThrow(() -> new UsernameNotFoundException("User not found"));
		boolean tenantActive = u.getTenantId() == null
				|| tenants.findById(u.getTenantId()).map(t -> t.isActive()).orElse(false);
		return new CrmUserDetails(u.getId(), u.getUsername(), u.getPasswordHash(), u.isActive() && tenantActive,
				u.getTenantId(), u.getRole(), u.getFullName());
	}
}
