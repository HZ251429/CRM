package com.hz.crm.enquiry;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EnquiryRepository extends JpaRepository<Enquiry, Long> {

	List<Enquiry> findByTenantIdOrderByCreatedAtDesc(Long tenantId);

	List<Enquiry> findByCustomerId(Long customerId);

	@org.springframework.transaction.annotation.Transactional
	@org.springframework.data.jpa.repository.Modifying
	@org.springframework.data.jpa.repository.Query("delete from Enquiry e where e.customerId = ?1")
	void deleteByCustomerId(Long customerId);
}
