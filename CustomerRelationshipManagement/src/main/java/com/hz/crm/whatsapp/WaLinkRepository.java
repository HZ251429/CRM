package com.hz.crm.whatsapp;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WaLinkRepository extends JpaRepository<WaLink, Long> {

	Optional<WaLink> findByPhone(String phone);

	Optional<WaLink> findByUserId(Long userId);
}