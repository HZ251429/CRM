package com.hz.crm.whatsapp;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A one-time code shown to a signed-in user in the web app. Sending it from WhatsApp links that number to the
 * user: it proves both who the user is (they were signed in) and that they hold the phone (the message came from it).
 */
@Entity
@Table(name = "wa_link_codes")
public class WaLinkCode {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 8 characters without the dash, upper case */
	@Column(nullable = false, unique = true, length = 16)
	private String code;

	@Column(nullable = false)
	private Long userId;

	@Column(nullable = false)
	private LocalDateTime expiresAt;

	public Long getId() { return id; }
	public String getCode() { return code; }
	public void setCode(String code) { this.code = code; }
	public Long getUserId() { return userId; }
	public void setUserId(Long userId) { this.userId = userId; }
	public LocalDateTime getExpiresAt() { return expiresAt; }
	public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}
