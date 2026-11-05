package com.hz.crm.security;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.hz.crm.user.AppUser;
import com.hz.crm.user.AppUserRepository;
import com.hz.crm.user.Role;

/**
 * Resolves the current user and which users' data they may see:
 * a tenant admin sees the whole tenant, a member sees self + all subordinates (recursively).
 */
@Service
public class AccessService {

	public record Scope(CrmUserDetails me, Long tenantId, Set<Long> ownerIds, List<AppUser> tenantUsers) {

		public boolean canSeeOwner(Long ownerId) {
			return ownerId != null && ownerIds.contains(ownerId);
		}

		public boolean owns(Long entityTenantId, Long ownerId) {
			return tenantId.equals(entityTenantId) && canSeeOwner(ownerId);
		}
	}

	private final AppUserRepository users;

	public AccessService(AppUserRepository users) {
		this.users = users;
	}

	public CrmUserDetails current() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !(auth.getPrincipal() instanceof CrmUserDetails d)) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		return d;
	}

	/** Scope for CRM data; the super admin has no tenant and therefore no CRM data. */
	public Scope scope() {
		CrmUserDetails me = current();
		if (me.getTenantId() == null) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Super admin has no tenant data");
		}
		List<AppUser> all = users.findByTenantId(me.getTenantId());
		Set<Long> visible = me.getRole() == Role.TENANT_ADMIN
				? all.stream().map(AppUser::getId).collect(Collectors.toSet())
				: subtree(me.getId(), all);
		return new Scope(me, me.getTenantId(), visible, all);
	}

	/** The user and all direct and indirect subordinates. */
	public Set<Long> subtree(Long rootId, List<AppUser> all) {
		Map<Long, List<Long>> children = new HashMap<>();
		for (AppUser u : all) {
			if (u.getManagerId() != null) {
				children.computeIfAbsent(u.getManagerId(), k -> new java.util.ArrayList<>()).add(u.getId());
			}
		}
		Set<Long> result = new LinkedHashSet<>();
		Deque<Long> stack = new ArrayDeque<>();
		stack.push(rootId);
		while (!stack.isEmpty()) {
			Long id = stack.pop();
			if (result.add(id)) {
				children.getOrDefault(id, List.of()).forEach(stack::push);
			}
		}
		return new HashSet<>(result);
	}
}
