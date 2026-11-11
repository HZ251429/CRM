package com.hz.crm.whatsapp;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WaOutboxRepository extends JpaRepository<WaOutbox, Long> {

	/** messages for a number newer than the given id, oldest first (the test chat polls with the last id it has) */
	List<WaOutbox> findTop50ByPhoneAndIdGreaterThanOrderByIdAsc(String phone, Long afterId);

	List<WaOutbox> findTop1ByPhoneOrderByIdDesc(String phone);

	/** waiting to be delivered: not sent, not given up on, due now (no retry pause pending), oldest first */
	@org.springframework.data.jpa.repository.Query("select o from WaOutbox o where o.sentAt is null"
			+ " and (o.attempts is null or o.attempts < ?1) and (o.nextTryAt is null or o.nextTryAt <= ?2) order by o.id")
	List<WaOutbox> findWaiting(int maxAttempts, java.time.LocalDateTime now, org.springframework.data.domain.Pageable page);
}
