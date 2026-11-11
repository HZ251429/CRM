package com.hz.crm.whatsapp;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WaSessionRepository extends JpaRepository<WaSession, String> {
}