package com.hz.crm.whatsapp;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface WaInboundRepository extends JpaRepository<WaInbound, String> {

	@Transactional
	@Modifying
	@Query("delete from WaInbound i where i.receivedAt < ?1")
	int deleteOlderThan(LocalDateTime time);
}
