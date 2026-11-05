package com.hz.crm.user;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

	Optional<AppUser> findByUsernameIgnoreCase(String username);

	boolean existsByUsernameIgnoreCase(String username);

	boolean existsByRole(Role role);

	List<AppUser> findByTenantId(Long tenantId);

	long countByTenantId(Long tenantId);
}
