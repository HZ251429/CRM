package com.hz.crm.task;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesTaskRepository extends JpaRepository<SalesTask, Long> {

	List<SalesTask> findByTenantId(Long tenantId);

	/** Per tenant: [tenantId, count]. */
	@org.springframework.data.jpa.repository.Query("select x.tenantId, count(x) from SalesTask x group by x.tenantId")
	List<Object[]> countByTenant();

	List<SalesTask> findByParentId(Long parentId);

	boolean existsByParentId(Long parentId);
}
