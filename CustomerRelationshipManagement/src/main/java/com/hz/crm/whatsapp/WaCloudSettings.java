package com.hz.crm.whatsapp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Connection to Meta's WhatsApp Cloud API. All values come from environment variables (see deploy/README.md);
 * the connection is switched on only when the phone number id and access token are set.
 */
@Component
public class WaCloudSettings {

	private final String phoneNumberId;
	private final String accessToken;
	private final String appSecret;
	private final String verifyToken;
	private final String graphUrl;
	private final String apiVersion;
	private final String templateLanguage;

	public WaCloudSettings(@Value("${crm.whatsapp.phone-number-id:}") String phoneNumberId,
			@Value("${crm.whatsapp.access-token:}") String accessToken,
			@Value("${crm.whatsapp.app-secret:}") String appSecret,
			@Value("${crm.whatsapp.verify-token:}") String verifyToken,
			@Value("${crm.whatsapp.graph-url:https://graph.facebook.com}") String graphUrl,
			@Value("${crm.whatsapp.api-version:v23.0}") String apiVersion,
			@Value("${crm.whatsapp.template-language:en}") String templateLanguage) {
		this.phoneNumberId = phoneNumberId.trim();
		this.accessToken = accessToken.trim();
		this.appSecret = appSecret.trim();
		this.verifyToken = verifyToken.trim();
		this.graphUrl = graphUrl.replaceAll("/+$", "");
		this.apiVersion = apiVersion.trim();
		this.templateLanguage = templateLanguage.trim();
	}

	/** true when messages can be sent through Meta */
	public boolean sendingEnabled() {
		return !phoneNumberId.isEmpty() && !accessToken.isEmpty();
	}

	/** true when Meta's incoming messages can be accepted (their signature can be checked) */
	public boolean receivingEnabled() {
		return !appSecret.isEmpty() && !verifyToken.isEmpty();
	}

	public String messagesUrl() {
		return graphUrl + "/" + apiVersion + "/" + phoneNumberId + "/messages";
	}

	public String phoneNumberId() { return phoneNumberId; }
	public String accessToken() { return accessToken; }
	public String appSecret() { return appSecret; }
	public String verifyToken() { return verifyToken; }
	public String templateLanguage() { return templateLanguage; }
}
