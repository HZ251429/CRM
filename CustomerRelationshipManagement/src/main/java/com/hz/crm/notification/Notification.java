package com.hz.crm.notification;

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

/** Something a person below the recipient did (added a customer, a team member or an activity). */
@Entity
@Table(name = "notifications", indexes = @Index(name = "ix_notifications_recipient", columnList = "recipientId, createdAt"))
public class Notification {

	public enum Type { CUSTOMER_ADDED, MEMBER_ADDED, ACTIVITY_ADDED }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long tenantId;

	/** the lead who receives it */
	@Column(nullable = false)
	private Long recipientId;

	/** the team member who did it */
	@Column(nullable = false)
	private Long actorId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private Type type;

	@Column(nullable = false, length = 300)
	private String message;

	/** id of the customer, user or activity it is about */
	private Long entityId;

	@Column(nullable = false)
	private LocalDateTime createdAt;

	private boolean read;

	@PrePersist
	void onCreate() {
		createdAt = LocalDateTime.now();
	}

	public Long getId() { return id; }
	public Long getTenantId() { return tenantId; }
	public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
	public Long getRecipientId() { return recipientId; }
	public void setRecipientId(Long recipientId) { this.recipientId = recipientId; }
	public Long getActorId() { return actorId; }
	public void setActorId(Long actorId) { this.actorId = actorId; }
	public Type getType() { return type; }
	public void setType(Type type) { this.type = type; }
	public String getMessage() { return message; }
	public void setMessage(String message) { this.message = message; }
	public Long getEntityId() { return entityId; }
	public void setEntityId(Long entityId) { this.entityId = entityId; }
	public LocalDateTime getCreatedAt() { return createdAt; }
	public boolean isRead() { return read; }
	public void setRead(boolean read) { this.read = read; }
}
