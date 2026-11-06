package com.hz.crm.user;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hz.crm.security.AccessService;
import com.hz.crm.security.CrmUserDetails;
import com.hz.crm.tenant.TenantRepository;

@RestController
@RequestMapping("/api/me")
public class MeController {

	private final AccessService access;
	private final TenantRepository tenants;
	private final AppUserRepository users;

	public MeController(AccessService access, TenantRepository tenants, AppUserRepository users) {
		this.users = users;
		this.access = access;
		this.tenants = tenants;
	}

	@GetMapping
	public Map<String, Object> me() {
		CrmUserDetails me = access.current();
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("id", me.getId());
		r.put("username", me.getUsername());
		r.put("fullName", me.getFullName());
		r.put("role", me.getRole());
		r.put("tenantId", me.getTenantId());
		r.put("tenantName", me.getTenantId() == null ? null
				: tenants.findById(me.getTenantId()).map(t -> t.getName()).orElse(null));
		r.put("tenantCode", me.getTenantId() == null ? null
				: tenants.findById(me.getTenantId()).map(t -> t.getCode()).orElse(null));
		// whether anyone reports to this user (the admin leads everyone in the tenant)
		r.put("hasTeam", me.getTenantId() != null && users.findByTenantId(me.getTenantId()).stream()
				.anyMatch(u -> !u.getId().equals(me.getId())
						&& (me.getRole() == Role.TENANT_ADMIN || me.getId().equals(u.getManagerId()))));
		return r;
	}
}
