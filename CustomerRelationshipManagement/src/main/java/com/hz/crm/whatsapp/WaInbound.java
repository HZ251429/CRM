package com.hz.crm.whatsapp;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** WhatsApp message ids already handled; Meta may deliver the same message more than once. */
@Entity
@Table(name = "wa_inbound")
public class WaInbound {

	@Id
	@Column(length = 200)
	private String messageId;

	@Column(nullable = false)
	private LocalDateTime receivedAt;

	protected WaInbound() {
	}

	public WaInbound(String messageId) {
		this.messageId = messageId;
		this.receivedAt = LocalDateTime.now();
	}

	public String getMessageId() { return messageId; }
	public LocalDateTime getReceivedAt() { return receivedAt; }
}
