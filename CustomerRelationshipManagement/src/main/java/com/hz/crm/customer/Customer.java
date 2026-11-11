package com.hz.crm.customer;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Entity
@Table(name = "customers", indexes = @Index(name = "ix_customers_tenant_owner", columnList = "tenantId, ownerId"))
public class Customer {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** first word of the name (the form has one Name field, see setName) */
	@NotBlank(message = "Enter the customer's name")
	@Size(max = 100)
	private String firstName;

	/** rest of the name; empty for a one-word name */
	@Size(max = 150)
	private String lastName = "";

	@Email(message = "Enter a valid email address")
	@Size(max = 254)
	private String email;

	/** mobile number */
	@Size(max = 25)
	@jakarta.validation.constraints.Pattern(regexp = "\\s*|[+0-9 ()\\-.]{7,25}", message = "Enter a valid mobile number")
	private String phone;

	@Size(max = 500)
	@Column(length = 500)
	private String address;

	@Size(max = 150)
	private String company;
	private LocalDateTime createdAt;

	private Long tenantId;
	private Long ownerId;

	public Long getTenantId() { return tenantId; }
	public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
	public Long getOwnerId() { return ownerId; }
	public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
	@PrePersist
	void onCreate() {
		createdAt = LocalDateTime.now();
		tidy();
	}

	@jakarta.persistence.PreUpdate
	void onUpdate() {
		tidy();
	}

	/** trims the fields and stores empty optional ones as "nothing" */
	private void tidy() {
		firstName = firstName == null ? null : firstName.trim();
		lastName = lastName == null ? "" : lastName.trim();
		email = blankToNull(email);
		phone = blankToNull(phone);
		address = blankToNull(address);
		company = blankToNull(company);
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}

	/** The full name, e.g. "Meera Iyer" (or just "Meera"). */
	public String getName() {
		String f = firstName == null ? "" : firstName.trim();
		String l = lastName == null ? "" : lastName.trim();
		return (f + " " + l).trim();
	}

	/** Sets the name from one field: the first word becomes the first name, the rest the last name. */
	public void setName(String name) {
		if (name == null) {
			return;
		}
		String n = name.trim().replaceAll("\\s+", " ");
		int space = n.indexOf(' ');
		firstName = space < 0 ? n : n.substring(0, space);
		lastName = space < 0 ? "" : n.substring(space + 1);
	}

	/** A customer needs a name and at least one way to reach them. */
	@com.fasterxml.jackson.annotation.JsonIgnore
	@jakarta.validation.constraints.AssertTrue(message = "Add at least a mobile number, email or address")
	public boolean isReachable() {
		return notBlank(phone) || notBlank(email) || notBlank(address);
	}

	private static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}

	public String getAddress() { return address; }
	public void setAddress(String address) { this.address = address; }

	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }
	public String getFirstName() { return firstName; }
	public void setFirstName(String firstName) { this.firstName = firstName; }
	public String getLastName() { return lastName; }
	public void setLastName(String lastName) { this.lastName = lastName; }
	public String getEmail() { return email; }
	public void setEmail(String email) { this.email = email; }
	public String getPhone() { return phone; }
	public void setPhone(String phone) { this.phone = phone; }
	public String getCompany() { return company; }
	public void setCompany(String company) { this.company = company; }
	public LocalDateTime getCreatedAt() { return createdAt; }
}
