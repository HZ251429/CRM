package com.hz.crm.activity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A log entry about an interaction with a customer: a meeting, call, visit, email or note. */
@Entity
@Table(name = "activities")
public class Activity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@NotNull
	private Long customerId;

	/** MEETING, CALL, VISIT, EMAIL, NOTE (TASK kept for older entries) */
	@Pattern(regexp = "MEETING|CALL|VISIT|EMAIL|NOTE|TASK", message = "Unknown activity type")
	private String type = "MEETING";

	/** short title of the entry */
	@NotBlank
	@Size(max = 200)
	private String subject;

	/** who took part, free text, e.g. "Ravi (CFO), Priya" */
	@Size(max = 500)
	@Column(length = 500)
	private String attendees;

	/**
	 * what was discussed. 2000 matches the existing column; ddl-auto=update cannot widen it, so a longer limit needs
	 * a migration.
	 */
	@Size(max = 2000)
	@Column(length = 2000)
	private String notes;

	/** decisions and follow-up actions */
	@Size(max = 2000)
	@Column(length = 2000)
	private String outcome;

	/** optional sale recorded with this activity: which product ... */
	private Long productId;

	/** ... and how many units of it were sold; counts toward sales tasks for that product */
	@Min(1)
	@Max(1_000_000)
	private Integer unitsSold;

	/** total value of the sale (INR) */
	@DecimalMin("0")
	@Digits(integer = 12, fraction = 2)
	@Column(precision = 14, scale = 2)
	private BigDecimal saleAmount;

	/** how much of the sale amount has been paid; the rest is on credit */
	@DecimalMin("0")
	@Digits(integer = 12, fraction = 2)
	@Column(precision = 14, scale = 2)
	private BigDecimal amountPaid;

	/** when it happened; set to "now" automatically when not provided */
	private LocalDateTime activityTime;

	/** legacy column from the old task list; no longer used, kept so existing rows and the NOT NULL column stay valid */
	@JsonIgnore
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
	public String getAttendees() { return attendees; }
	public void setAttendees(String attendees) { this.attendees = attendees; }
	public String getNotes() { return notes; }
	public void setNotes(String notes) { this.notes = notes; }
	public String getOutcome() { return outcome; }
	public void setOutcome(String outcome) { this.outcome = outcome; }
	public Long getProductId() { return productId; }
	public void setProductId(Long productId) { this.productId = productId; }
	public Integer getUnitsSold() { return unitsSold; }
	public void setUnitsSold(Integer unitsSold) { this.unitsSold = unitsSold; }
	public BigDecimal getSaleAmount() { return saleAmount; }
	public void setSaleAmount(BigDecimal saleAmount) { this.saleAmount = saleAmount; }
	public BigDecimal getAmountPaid() { return amountPaid; }
	public void setAmountPaid(BigDecimal amountPaid) { this.amountPaid = amountPaid; }
	/** amount still owed by the customer */
	@com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
	public BigDecimal getCreditAmount() {
		return saleAmount == null ? null : saleAmount.subtract(amountPaid == null ? saleAmount : amountPaid);
	}
	public LocalDateTime getActivityTime() { return activityTime; }
	public void setActivityTime(LocalDateTime activityTime) { this.activityTime = activityTime; }
}
