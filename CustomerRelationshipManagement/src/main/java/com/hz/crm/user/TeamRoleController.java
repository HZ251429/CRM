package com.hz.crm.user;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.hz.crm.security.AccessService;
import com.hz.crm.security.AccessService.Scope;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Job roles that can be given to team members. Any user can introduce roles for their own team (see
 * {@link TeamRoleService} for who sees which roles); the creator or the admin can rename or delete them.
 */
@RestController
@RequestMapping("/api/roles")
public class TeamRoleController {

	public record RoleView(Long id, String name, long memberCount, Long createdById, String createdByName,
			boolean canEdit) {
	}

	public record RoleRequest(@NotBlank @Size(max = 50) String name) {
	}

	private final TeamRoleRepository roles;
	private final TeamRoleService roleAccess;
	private final AccessService access;

	public TeamRoleController(TeamRoleRepository roles, TeamRoleService roleAccess, AccessService access) {
		this.roles = roles;
		this.roleAccess = roleAccess;
		this.access = access;
	}

	private RoleView view(TeamRole r, Scope s) {
		Map<Long, String> names = s.tenantUsers().stream().collect(Collectors.toMap(AppUser::getId,
				u -> u.getRole() == Role.TENANT_ADMIN ? "Admin (company-wide)"
						: u.getFullName() != null ? u.getFullName() : u.getUsername()));
		long count = s.tenantUsers().stream().filter(u -> r.getId().equals(u.getTeamRoleId())).count();
		String by = r.getCreatedById() == null ? "Company-wide" : names.getOrDefault(r.getCreatedById(), "Unknown");
		return new RoleView(r.getId(), r.getName(), count, r.getCreatedById(), by, roleAccess.canManage(r, s));
	}

	/** A role the current user can see (otherwise 404, so other teams' roles are not revealed). */
	private TeamRole find(Long id, Scope s) {
		return roles.findById(id).filter(r -> s.tenantId().equals(r.getTenantId()) && roleAccess.canSee(r, s))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	private TeamRole findManageable(Long id, Scope s) {
		TeamRole r = find(id, s);
		if (!roleAccess.canManage(r, s)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the person who created this role can change it");
		}
		return r;
	}

	@GetMapping
	public List<RoleView> list() {
		Scope s = access.scope();
		return roleAccess.visible(s).stream().map(r -> view(r, s)).toList();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public RoleView create(@Valid @RequestBody RoleRequest req) {
		Scope s = access.scope();
		String name = req.name().trim();
		if (roles.existsByTenantIdAndNameIgnoreCase(s.tenantId(), name)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "A role with this name already exists in the company");
		}
		TeamRole r = new TeamRole();
		r.setTenantId(s.tenantId());
		r.setName(name);
		r.setCreatedById(s.me().getId());
		return view(roles.save(r), s);
	}

	@PutMapping("/{id}")
	public RoleView rename(@PathVariable Long id, @Valid @RequestBody RoleRequest req) {
		Scope s = access.scope();
		TeamRole r = findManageable(id, s);
		String name = req.name().trim();
		if (!r.getName().equalsIgnoreCase(name) && roles.existsByTenantIdAndNameIgnoreCase(s.tenantId(), name)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "A role with this name already exists in the company");
		}
		r.setName(name);
		return view(roles.save(r), s);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		Scope s = access.scope();
		TeamRole r = findManageable(id, s);
		if (view(r, s).memberCount() > 0) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Role is assigned to team members. Reassign them first.");
		}
		roles.delete(r);
		return ResponseEntity.noContent().build();
	}
}
