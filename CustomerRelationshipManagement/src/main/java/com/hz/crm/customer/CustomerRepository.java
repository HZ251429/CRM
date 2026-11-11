package com.hz.crm.customer;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

	@Query("""
			select c from Customer c
			where c.tenantId = :t and c.ownerId in :owners
			  and (lower(concat(c.firstName, ' ', coalesce(c.lastName, ''))) like lower(concat('%', :q, '%'))
			    or lower(coalesce(c.email, '')) like lower(concat('%', :q, '%'))
			    or lower(coalesce(c.phone, '')) like lower(concat('%', :q, '%'))
			    or lower(coalesce(c.address, '')) like lower(concat('%', :q, '%'))
			    or lower(coalesce(c.company, '')) like lower(concat('%', :q, '%')))
			""")
	Page<Customer> search(@Param("t") Long tenantId, @Param("owners") Collection<Long> owners,
			@Param("q") String q, Pageable pageable);

	List<Customer> findByTenantIdAndOwnerIdIn(Long tenantId, Collection<Long> owners, Sort sort);

	boolean existsByTenantIdAndEmail(Long tenantId, String email);

	/** Customers per tenant: [tenantId, count]. */
	@Query("select c.tenantId, count(c) from Customer c group by c.tenantId")
	List<Object[]> countByTenant();

	List<Customer> findByTenantId(Long tenantId, Pageable page);

	/** Customers per owner: [ownerId, count]. */
	@Query("select c.ownerId, count(c) from Customer c where c.tenantId = ?1 and c.ownerId in ?2 group by c.ownerId")
	List<Object[]> countByOwner(Long tenantId, Collection<Long> owners);

	/** Customers added per owner in [from, to): [ownerId, count]. */
	@Query("select c.ownerId, count(c) from Customer c where c.tenantId = ?1 and c.ownerId in ?2"
			+ " and c.createdAt >= ?3 and c.createdAt < ?4 group by c.ownerId")
	List<Object[]> countByOwnerBetween(Long tenantId, Collection<Long> owners, java.time.LocalDateTime from,
			java.time.LocalDateTime to);}