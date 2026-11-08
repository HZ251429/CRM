package com.hz.crm.deal;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "deals", indexes = @Index(name = "ix_deals_tenant_owner", columnList = "tenantId, ownerId"))
public class Deal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@NotNull
	private Long customerId;

	@NotBlank
	private String title;

	private BigDecimal amount = BigDecimal.ZERO;

	/** NEW, QUALIFIED, PROPOSAL, NEGOTIATION, WON, LOST */
	private String stage = "NEW";

	private LocalDate expectedCloseDate;

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
	public String getTitle() { return title; }
	public void setTitle(String title) { this.title = title; }
	public BigDecimal getAmount() { return amount; }
	public void setAmount(BigDecimal amount) { this.amount = amount; }
	public String getStage() { return stage; }
	public void setStage(String stage) { this.stage = stage; }
	public LocalDate getExpectedCloseDate() { return expectedCloseDate; }
	public void setExpectedCloseDate(LocalDate expectedCloseDate) { this.expectedCloseDate = expectedCloseDate; }
}
