package com.hz.crm.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, Long> {

	boolean existsByNameIgnoreCase(String name);

	boolean existsByCodeIgnoreCase(String code);
}
