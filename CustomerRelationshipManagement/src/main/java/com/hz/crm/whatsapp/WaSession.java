package com.hz.crm.whatsapp;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Where a WhatsApp number is in a multi-step conversation (e.g. logging an activity), and the answers so far. */
@Entity
@Table(name = "wa_sessions")
public class WaSession {

	@Id
	@Column(length = 20)
	private String phone;

	@Column(nullable = false, length = 40)
	private String state = "IDLE";

	/** answers so far, as key=value&key=value (URL-encoded) */
	@Column(length = 4000)
	private String data = "";

	@Column(nullable = false)
	private LocalDateTime updatedAt = LocalDateTime.now();

	/** when this number last wrote to us through WhatsApp: within 24 hours we may reply with normal messages */
	private LocalDateTime lastInboundAt;

	protected WaSession() {
	}

	public LocalDateTime getLastInboundAt() { return lastInboundAt; }
	public void setLastInboundAt(LocalDateTime t) { this.lastInboundAt = t; }

	public WaSession(String phone) {
		this.phone = phone;
	}

	public String getPhone() { return phone; }
	public String getState() { return state; }
	public LocalDateTime getUpdatedAt() { return updatedAt; }

	public void setState(String state) {
		this.state = state;
		this.updatedAt = LocalDateTime.now();
	}

	public Map<String, String> data() {
		Map<String, String> m = new LinkedHashMap<>();
		if (data == null || data.isEmpty()) {
			return m;
		}
		for (String pair : data.split("&")) {
			int i = pair.indexOf('=');
			if (i > 0) {
				m.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
						URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
			}
		}
		return m;
	}

	public String get(String key) {
		return data().get(key);
	}

	public void put(String key, String value) {
		Map<String, String> m = data();
		if (value == null) {
			m.remove(key);
		} else {
			m.put(key, value);
		}
		data = m.entrySet().stream()
				.map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
						+ URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
				.collect(Collectors.joining("&"));
		updatedAt = LocalDateTime.now();
	}

	/** Back to the start: no conversation in progress. */
	public void reset() {
		state = "IDLE";
		data = "";
		updatedAt = LocalDateTime.now();
	}
}
