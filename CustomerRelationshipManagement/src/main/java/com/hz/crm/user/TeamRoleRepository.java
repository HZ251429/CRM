package com.hz.crm.user;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRoleRepository extends JpaRepository<TeamRole, Long> {

	List<TeamRole> findByTenantIdOrderByNameAsc(Long tenantId);

	boolean existsByTenantIdAndNameIgnoreCase(Long tenantId, String name);
}
