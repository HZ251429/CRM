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

	// what to send to this number (null = default, for rows from before these settings existed)

	/** alerts about the team: customers, members and activities/sales added below you */
	private Boolean alertsTeam;

	/** alerts when a task is assigned to you */
	private Boolean alertsTasks;

	/** an evening summary of the day */
	private Boolean summaryEnabled;

	/** hour of the day (0-23) the summary is sent */
	private Integer summaryHour;

	/** the last day a scheduled summary was sent (so it goes out once a day) */
	private java.time.LocalDate lastSummaryOn;

	@PrePersist
	void onCreate() {
		linkedAt = LocalDateTime.now();
	}

	public boolean isAlertsTeam() { return alertsTeam == null || alertsTeam; }
	public void setAlertsTeam(boolean v) { this.alertsTeam = v; }
	public boolean isAlertsTasks() { return alertsTasks == null || alertsTasks; }
	public void setAlertsTasks(boolean v) { this.alertsTasks = v; }
	public boolean isSummaryEnabled() { return summaryEnabled == null || summaryEnabled; }
	public void setSummaryEnabled(boolean v) { this.summaryEnabled = v; }
	public int getSummaryHour() { return summaryHour == null ? 19 : summaryHour; }
	public void setSummaryHour(int h) { this.summaryHour = h; }
	public java.time.LocalDate getLastSummaryOn() { return lastSummaryOn; }
	public void setLastSummaryOn(java.time.LocalDate d) { this.lastSummaryOn = d; }

	public Long getId() { return id; }
	public String getPhone() { return phone; }
	public void setPhone(String phone) { this.phone = phone; }
	public Long getUserId() { return userId; }
	public void setUserId(Long userId) { this.userId = userId; }
	public LocalDateTime getLinkedAt() { return linkedAt; }
}
