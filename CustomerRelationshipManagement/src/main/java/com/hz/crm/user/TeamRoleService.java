package com.hz.crm.user;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.hz.crm.security.AccessService.Scope;

/**
 * Which job roles a user can see and use. Any user can introduce roles: a role is visible to the person who made it,
 * everyone below them and everyone above them (so leads see their team's roles). Roles made by the admin, or made
 * before roles had an owner, are company-wide. The admin sees every role.
 */
@Service
public class TeamRoleService {

	private final TeamRoleRepository roles;

	public TeamRoleService(TeamRoleRepository roles) {
		this.roles = roles;
	}

	/** People whose roles this user can see: themselves, everyone below them, everyone above them, and admins. */
	private Set<Long> visibleCreators(Scope s) {
		Map<Long, AppUser> byId = s.tenantUsers().stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));
		Set<Long> ids = new HashSet<>(s.ownerIds()); // self + everyone below
		AppUser u = byId.get(s.me().getId());
		while (u != null && u.getManagerId() != null && ids.add(u.getManagerId())) {
			u = byId.get(u.getManagerId());
		}
		s.tenantUsers().stream().filter(x -> x.getRole() == Role.TENANT_ADMIN).forEach(x -> ids.add(x.getId()));
		return ids;
	}

	public boolean isAdmin(Scope s) {
		return s.me().getRole() == Role.TENANT_ADMIN;
	}

	public List<TeamRole> visible(Scope s) {
		List<TeamRole> all = roles.findByTenantIdOrderByNameAsc(s.tenantId());
		if (isAdmin(s)) {
			return all;
		}
		Set<Long> creators = visibleCreators(s);
		return all.stream().filter(r -> r.getCreatedById() == null || creators.contains(r.getCreatedById())).toList();
	}

	public boolean canSee(TeamRole r, Scope s) {
		return isAdmin(s) || r.getCreatedById() == null || visibleCreators(s).contains(r.getCreatedById());
	}

	/** Only the person who introduced a role, or the admin, may rename or delete it. */
	public boolean canManage(TeamRole r, Scope s) {
		return isAdmin(s) || s.me().getId().equals(r.getCreatedById());
	}
}
