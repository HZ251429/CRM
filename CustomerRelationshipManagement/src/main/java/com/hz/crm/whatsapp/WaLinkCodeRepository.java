package com.hz.crm.whatsapp;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface WaLinkCodeRepository extends JpaRepository<WaLinkCode, Long> {

	Optional<WaLinkCode> findByCode(String code);

	/** a user's old codes and everyone's expired ones */
	@Transactional
	@Modifying
	@Query("delete from WaLinkCode c where c.userId = ?1 or c.expiresAt < ?2")
	void deleteByUserIdOrExpired(Long userId, LocalDateTime now);
}