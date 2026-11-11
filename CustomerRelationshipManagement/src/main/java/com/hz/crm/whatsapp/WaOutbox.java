package com.hz.crm.whatsapp;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * A message the CRM starts (an alert or the daily summary), waiting to be delivered to a WhatsApp number.
 * Until a real WhatsApp number is connected, messages stay here and the test chat shows them. When the Cloud API is
 * connected, a sender delivers each one through the approved template named in {@link #template} and sets sentAt.
 */
@Entity
@Table(name = "wa_outbox", indexes = @Index(name = "ix_wa_outbox_phone", columnList = "phone, id"))
public class WaOutbox {

	public enum Kind { ALERT, SUMMARY }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 20)
	private String phone;

	@Column(nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Kind kind;

	/** the WhatsApp template it will be sent with (business-started messages must use approved templates) */
	@Column(nullable = false, length = 60)
	private String template;

	@Column(nullable = false, length = 4096)
	private String text;

	@Column(nullable = false)
	private LocalDateTime createdAt;

	/** set once delivered through WhatsApp; stays empty in test mode */
	private LocalDateTime sentAt;

	/** delivery attempts so far (null = none) */
	private Integer attempts;

	/** why the last attempt failed, or why the message was skipped */
	@Column(length = 500)
	private String lastError;

	/** after a failed attempt: not before this time (null = as soon as possible) */
	private LocalDateTime nextTryAt;

	public LocalDateTime getNextTryAt() { return nextTryAt; }
	public void setNextTryAt(LocalDateTime t) { this.nextTryAt = t; }

	public int getAttempts() { return attempts == null ? 0 : attempts; }
	public void setAttempts(int attempts) { this.attempts = attempts; }
	public String getLastError() { return lastError; }
	public void setLastError(String lastError) {
		this.lastError = lastError == null || lastError.length() <= 500 ? lastError : lastError.substring(0, 500);
	}

	@PrePersist
	void onCreate() {
		createdAt = LocalDateTime.now();
	}

	public Long getId() { return id; }
	public String getPhone() { return phone; }
	public void setPhone(String phone) { this.phone = phone; }
	public Long getUserId() { return userId; }
	public void setUserId(Long userId) { this.userId = userId; }
	public Kind getKind() { return kind; }
	public void setKind(Kind kind) { this.kind = kind; }
	public String getTemplate() { return template; }
	public void setTemplate(String template) { this.template = template; }
	public String getText() { return text; }
	public void setText(String text) { this.text = text; }
	public LocalDateTime getCreatedAt() { return createdAt; }
	public LocalDateTime getSentAt() { return sentAt; }
	public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }
}
