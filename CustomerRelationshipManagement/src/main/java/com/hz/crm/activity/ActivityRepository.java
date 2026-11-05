package com.hz.crm.activity;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface ActivityRepository extends JpaRepository<Activity, Long> {

	List<Activity> findByTenantIdAndOwnerIdIn(Long tenantId, Collection<Long> owners);

	List<Activity> findByTenantIdAndOwnerIdInAndCustomerId(Long tenantId, Collection<Long> owners, Long customerId);

	@Transactional
	@Modifying
	@Query("delete from Activity a where a.customerId = ?1")
	void deleteByCustomerId(Long customerId);
}