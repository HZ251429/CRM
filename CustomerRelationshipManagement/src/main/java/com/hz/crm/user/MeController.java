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

	public MeController(AccessService access, TenantRepository tenants) {
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
		return r;
	}
}
