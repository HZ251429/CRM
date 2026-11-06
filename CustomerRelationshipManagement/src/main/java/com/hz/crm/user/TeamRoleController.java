package com.hz.crm.user;

import java.util.List;

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

/** Tenant admin: manage the job roles that can be assigned to team members. */
@RestController
@RequestMapping("/api/roles")
public class TeamRoleController {

	public record RoleView(Long id, String name, long memberCount) {
	}

	public record RoleRequest(@NotBlank @Size(max = 50) String name) {
	}

	private final TeamRoleRepository roles;
	private final AccessService access;

	public TeamRoleController(TeamRoleRepository roles, AccessService access) {
		this.roles = roles;
		this.access = access;
	}

	private RoleView view(TeamRole r, Scope s) {
		long count = s.tenantUsers().stream().filter(u -> r.getId().equals(u.getTeamRoleId())).count();
		return new RoleView(r.getId(), r.getName(), count);
	}

	private Scope adminScope() {
		Scope s = access.scope();
		if (s.me().getRole() != Role.TENANT_ADMIN) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the admin can manage roles");
		}
		return s;
	}

	private TeamRole find(Long id, Scope s) {
		return roles.findById(id).filter(r -> s.tenantId().equals(r.getTenantId()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@GetMapping
	public List<RoleView> list() {
		Scope s = access.scope(); // everyone may read the roles, e.g. to pick one for a new team member
		return roles.findByTenantIdOrderByNameAsc(s.tenantId()).stream().map(r -> view(r, s)).toList();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public RoleView create(@Valid @RequestBody RoleRequest req) {
		Scope s = adminScope();
		String name = req.name().trim();
		if (roles.existsByTenantIdAndNameIgnoreCase(s.tenantId(), name)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Role already exists");
		}
		TeamRole r = new TeamRole();
		r.setTenantId(s.tenantId());
		r.setName(name);
		return view(roles.save(r), s);
	}

	@PutMapping("/{id}")
	public RoleView rename(@PathVariable Long id, @Valid @RequestBody RoleRequest req) {
		Scope s = adminScope();
		TeamRole r = find(id, s);
		String name = req.name().trim();
		if (!r.getName().equalsIgnoreCase(name) && roles.existsByTenantIdAndNameIgnoreCase(s.tenantId(), name)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Role already exists");
		}
		r.setName(name);
		return view(roles.save(r), s);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		Scope s = adminScope();
		TeamRole r = find(id, s);
		if (view(r, s).memberCount() > 0) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Role is assigned to team members. Reassign them first.");
		}
		roles.delete(r);
		return ResponseEntity.noContent().build();
	}
}
