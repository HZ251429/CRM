package com.hz.crm.deal;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface DealRepository extends JpaRepository<Deal, Long> {

	List<Deal> findByTenantIdAndOwnerIdIn(Long tenantId, Collection<Long> owners);

	List<Deal> findByTenantIdAndOwnerIdInAndCustomerId(Long tenantId, Collection<Long> owners, Long customerId);

	@Transactional
	@Modifying
	@Query("delete from Deal d where d.customerId = ?1")
	void deleteByCustomerId(Long customerId);
}