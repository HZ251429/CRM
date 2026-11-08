package com.hz.crm.product;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {

	List<Product> findByTenantIdOrderByNameAsc(Long tenantId);

	/** Per tenant: [tenantId, count]. */
	@org.springframework.data.jpa.repository.Query("select x.tenantId, count(x) from Product x group by x.tenantId")
	List<Object[]> countByTenant();

	boolean existsByTenantIdAndNameIgnoreCase(Long tenantId, String name);
}
