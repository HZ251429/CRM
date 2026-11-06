package com.hz.crm.task;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesTaskRepository extends JpaRepository<SalesTask, Long> {

	List<SalesTask> findByTenantId(Long tenantId);

	List<SalesTask> findByParentId(Long parentId);

	boolean existsByParentId(Long parentId);
}
