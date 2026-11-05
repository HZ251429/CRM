package com.hz.crm.user;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import com.hz.crm.tenant.TenantRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Tenant admin: manage team members and the reporting hierarchy. */
@RestController
@RequestMapping("/api/users")
public class UserController {

	public record UserView(Long id, String username, String fullName, Role role, Long managerId, boolean active,
			Long teamRoleId, String teamRoleName) {
	}

	public record CreateUserRequest(@NotBlank String username, @NotBlank @Size(min = 6) String password,
			@NotBlank String fullName, Long managerId, Long teamRoleId) {
	}

	public record UpdateUserRequest(String fullName, Long managerId, Long teamRoleId, Boolean active,
			String password) {
	}

	private final AppUserRepository users;
	private final PasswordEncoder encoder;
	private final AccessService access;
	private final TenantRepository tenants;
	private final TeamRoleRepository teamRoles;

	public UserController(AppUserRepository users, PasswordEncoder encoder, AccessService access,
			TenantRepository tenants, TeamRoleRepository teamRoles) {
		this.users = users;
		this.encoder = encoder;
		this.access = access;
		this.tenants = tenants;
		this.teamRoles = teamRoles;
	}

	public static UserView view(AppUser u, Map<Long, String> roleNames) {
		return new UserView(u.getId(), u.getUsername(), u.getFullName(), u.getRole(), u.getManagerId(), u.isActive(),
				u.getTeamRoleId(), u.getTeamRoleId() == null ? null : roleNames.get(u.getTeamRoleId()));
	}

	private Map<Long, String> roleNames(Long tenantId) {
		return teamRoles.findByTenantIdOrderByNameAsc(tenantId).stream()
				.collect(Collectors.toMap(TeamRole::getId, TeamRole::getName));
	}

	private void checkTeamRole(Long teamRoleId, Scope s) {
		if (teamRoleId != null
				&& teamRoles.findById(teamRoleId).filter(r -> s.tenantId().equals(r.getTenantId())).isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown role");
		}
	}

	private void checkManager(Long managerId, Scope s) {
		if (managerId != null && s.tenantUsers().stream().noneMatch(u -> u.getId().equals(managerId))) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Manager must belong to this tenant");
		}
	}

	@GetMapping
	public List<UserView> list() {
		Scope s = access.scope();
		Map<Long, String> names = roleNames(s.tenantId());
		return s.tenantUsers().stream().map(u -> view(u, names)).toList();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public UserView create(@Valid @RequestBody CreateUserRequest req) {
		Scope s = access.scope();
		String code = tenants.findById(s.tenantId()).map(t -> t.getCode())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant has no code"));
		String login = LoginNames.build(req.username(), code);
		if (users.existsByUsernameIgnoreCase(login)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
		}
		checkManager(req.managerId(), s);
		checkTeamRole(req.teamRoleId(), s);
		AppUser u = new AppUser();
		u.setUsername(login);
		u.setPasswordHash(encoder.encode(req.password()));
		u.setFullName(req.fullName().trim());
		u.setRole(Role.MEMBER);
		u.setTenantId(s.tenantId());
		u.setManagerId(req.managerId());
		u.setTeamRoleId(req.teamRoleId());
		return view(users.save(u), roleNames(s.tenantId()));
	}

	@PutMapping("/{id}")
	public UserView update(@PathVariable Long id, @RequestBody UpdateUserRequest req) {
		Scope s = access.scope();
		AppUser u = s.tenantUsers().stream().filter(x -> x.getId().equals(id)).findFirst()
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		if (req.fullName() != null && !req.fullName().isBlank()) {
			u.setFullName(req.fullName().trim());
		}
		if (u.getRole() == Role.MEMBER) {
			checkManager(req.managerId(), s);
			Set<Long> subtree = access.subtree(u.getId(), s.tenantUsers());
			if (req.managerId() != null && subtree.contains(req.managerId())) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"A user cannot report to themselves or to one of their own subordinates");
			}
			u.setManagerId(req.managerId());
			checkTeamRole(req.teamRoleId(), s);
			u.setTeamRoleId(req.teamRoleId());
			if (req.active() != null) {
				u.setActive(req.active());
			}
		}
		if (req.password() != null && !req.password().isBlank()) {
			if (req.password().length() < 6) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 6 characters");
			}
			u.setPasswordHash(encoder.encode(req.password()));
		}
		return view(users.save(u), roleNames(s.tenantId()));
	}
}
