package com.hz.crm.product;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {

	List<Product> findByTenantIdOrderByNameAsc(Long tenantId);

	boolean existsByTenantIdAndNameIgnoreCase(Long tenantId, String name);
}
