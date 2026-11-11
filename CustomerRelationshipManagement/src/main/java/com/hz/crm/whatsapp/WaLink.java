package com.hz.crm.whatsapp;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** A WhatsApp number linked to a CRM account. One number per account, one account per number. */
@Entity
@Table(name = "wa_links")
public class WaLink {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** digits with country code, e.g. 919876543210 */
	@Column(nullable = false, unique = true, length = 20)
	private String phone;

	@Column(nullable = false, unique = true)
	private Long userId;

	@Column(nullable = false)
	private LocalDateTime linkedAt;

	@PrePersist
	void onCreate() {
		linkedAt = LocalDateTime.now();
	}

	public Long getId() { return id; }
	public String getPhone() { return phone; }
	public void setPhone(String phone) { this.phone = phone; }
	public Long getUserId() { return userId; }
	public void setUserId(Long userId) { this.userId = userId; }
	public LocalDateTime getLinkedAt() { return linkedAt; }
}
