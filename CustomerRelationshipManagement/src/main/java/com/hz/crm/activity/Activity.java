package com.hz.crm.activity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "activities")
public class Activity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@NotNull
	private Long customerId;

	/** CALL, EMAIL, MEETING, TASK, NOTE */
	private String type = "TASK";

	@NotBlank
	private String subject;

	@Column(length = 2000)
	private String notes;

	/** when the activity happened / is scheduled; set to "now" automatically when not provided */
	private LocalDateTime activityTime;
	private boolean completed;

	private Long tenantId;
	private Long ownerId;

	public Long getTenantId() { return tenantId; }
	public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
	public Long getOwnerId() { return ownerId; }
	public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }
	public Long getCustomerId() { return customerId; }
	public void setCustomerId(Long customerId) { this.customerId = customerId; }
	public String getType() { return type; }
	public void setType(String type) { this.type = type; }
	public String getSubject() { return subject; }
	public void setSubject(String subject) { this.subject = subject; }
	public String getNotes() { return notes; }
	public void setNotes(String notes) { this.notes = notes; }
	public LocalDateTime getActivityTime() { return activityTime; }
	public void setActivityTime(LocalDateTime activityTime) { this.activityTime = activityTime; }
	public boolean isCompleted() { return completed; }
	public void setCompleted(boolean completed) { this.completed = completed; }
}
