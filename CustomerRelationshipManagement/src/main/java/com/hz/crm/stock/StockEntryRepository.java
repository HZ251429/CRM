package com.hz.crm.stock;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StockEntryRepository extends JpaRepository<StockEntry, Long> {

	List<StockEntry> findByTenantId(Long tenantId);

	List<StockEntry> findByTenantIdAndProductIdOrderByCreatedAtDesc(Long tenantId, Long productId);
}
