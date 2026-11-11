package com.hz.crm.whatsapp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Sends messages through Meta's WhatsApp Cloud API (POST /{phone-number-id}/messages). Turns the assistant's
 * {@link WaMessage} (text, reply buttons, list) and template messages into Meta's JSON format.
 */
@Component
public class WaCloudClient {

	/** Meta refused or could not be reached; the message says why (Meta's error text when there is one). */
	public static class SendException extends RuntimeException {
		public SendException(String message) {
			super(message);
		}
	}

	private final WaCloudSettings settings;
	private final RestClient http;

	public WaCloudClient(WaCloudSettings settings) {
		this.settings = settings;
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(10));
		factory.setReadTimeout(Duration.ofSeconds(20));
		this.http = RestClient.builder().requestFactory(factory).build();
	}

	/** An assistant reply (free-form: only allowed within 24 hours of the person's last message). */
	public void send(String to, WaMessage m) {
		Map<String, Object> body = base(to);
		if (m.list() != null && !m.list().rows().isEmpty()) {
			List<Map<String, Object>> rows = new ArrayList<>();
			for (WaMessage.Row r : m.list().rows()) {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("id", r.id());
				row.put("title", r.title());
				if (r.description() != null && !r.description().isBlank()) {
					row.put("description", r.description());
				}
				rows.add(row);
			}
			body.put("type", "interactive");
			body.put("interactive", Map.of("type", "list", "body", Map.of("text", nonEmpty(m.text())),
					"action", Map.of("button", m.list().buttonText(),
							"sections", List.of(Map.of("title", "Choose", "rows", rows)))));
		} else if (!m.buttons().isEmpty()) {
			List<Map<String, Object>> buttons = m.buttons().stream()
					.map(b -> Map.<String, Object>of("type", "reply", "reply", Map.of("id", b.id(), "title", b.title())))
					.toList();
			body.put("type", "interactive");
			body.put("interactive", Map.of("type", "button", "body", Map.of("text", nonEmpty(m.text())),
					"action", Map.of("buttons", buttons)));
		} else {
			body.put("type", "text");
			body.put("text", Map.of("body", nonEmpty(m.text()), "preview_url", false));
		}
		post(body);
	}

	/**
	 * A template message (needed when the CRM writes first, outside the 24-hour window).
	 *
	 * @param bodyParams      values for {{1}}, {{2}}, ... in the template body
	 * @param quickReplyIds   payloads for the template's quick-reply buttons, in order (may be empty)
	 */
	public void sendTemplate(String to, String template, List<String> bodyParams, List<String> quickReplyIds) {
		List<Map<String, Object>> components = new ArrayList<>();
		if (!bodyParams.isEmpty()) {
			components.add(Map.of("type", "body", "parameters",
					bodyParams.stream().map(p -> Map.<String, Object>of("type", "text", "text", templateText(p))).toList()));
		}
		for (int i = 0; i < quickReplyIds.size(); i++) {
			components.add(Map.of("type", "button", "sub_type", "quick_reply", "index", String.valueOf(i),
					"parameters", List.of(Map.of("type", "payload", "payload", quickReplyIds.get(i)))));
		}
		Map<String, Object> body = base(to);
		body.put("type", "template");
		Map<String, Object> t = new LinkedHashMap<>();
		t.put("name", template);
		t.put("language", Map.of("code", settings.templateLanguage()));
		if (!components.isEmpty()) {
			t.put("components", components);
		}
		body.put("template", t);
		post(body);
	}

	/** Meta rejects template values with line breaks, tabs or more than 4 spaces in a row. */
	static String templateText(String s) {
		String t = (s == null ? "" : s).replaceAll("\\s*\\n+\\s*", " · ").replace('\t', ' ').replaceAll(" {4,}", "   ").trim();
		return t.length() > 1000 ? t.substring(0, 999) + "…" : t.isEmpty() ? "-" : t;
	}

	private static String nonEmpty(String s) {
		return s == null || s.isBlank() ? "…" : s;
	}

	private static Map<String, Object> base(String to) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("messaging_product", "whatsapp");
		body.put("recipient_type", "individual");
		body.put("to", to);
		return body;
	}

	private void post(Map<String, Object> body) {
		if (!settings.sendingEnabled()) {
			throw new SendException("WhatsApp sending is not configured");
		}
		try {
			http.post().uri(settings.messagesUrl())
					.header("Authorization", "Bearer " + settings.accessToken())
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.retrieve()
					.toBodilessEntity();
		} catch (RestClientResponseException e) {
			String detail = e.getResponseBodyAsString();
			throw new SendException("Meta answered " + e.getStatusCode().value()
					+ (detail.isBlank() ? "" : ": " + (detail.length() > 400 ? detail.substring(0, 400) : detail)));
		} catch (RuntimeException e) {
			throw new SendException("Could not reach Meta: " + e.getMessage());
		}
	}
}
