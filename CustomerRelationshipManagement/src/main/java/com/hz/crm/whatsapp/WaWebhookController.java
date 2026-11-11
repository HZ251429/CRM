package com.hz.crm.whatsapp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hz.crm.user.LoginIdentifiers;
import com.hz.crm.whatsapp.WaMessage.Incoming;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Meta's webhook for the WhatsApp Cloud API.
 * <ul>
 * <li>GET: Meta checks the address once, with the verify token you entered in the app settings.</li>
 * <li>POST: incoming messages. Every request must carry Meta's signature (HMAC-SHA256 of the body with the App
 * Secret); anything else is refused. Meta is answered at once and the messages are handled in the background, one
 * number at a time and in order, each message only once.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/whatsapp/webhook")
public class WaWebhookController {

	private static final Logger log = LoggerFactory.getLogger(WaWebhookController.class);
	private static final int LANES = 4;

	private final WaCloudSettings settings;
	private final WaCloudClient client;
	private final WaAssistant assistant;
	private final WaSessionRepository sessions;
	private final WaInboundRepository inbound;
	private final JsonMapper json = JsonMapper.builder().build();
	/** messages from one number always go to the same lane, so they are handled in the order they were sent */
	private final ExecutorService[] lanes = new ExecutorService[LANES];

	public WaWebhookController(WaCloudSettings settings, WaCloudClient client, WaAssistant assistant,
			WaSessionRepository sessions, WaInboundRepository inbound) {
		this.settings = settings;
		this.client = client;
		this.assistant = assistant;
		this.sessions = sessions;
		this.inbound = inbound;
		for (int i = 0; i < LANES; i++) {
			lanes[i] = Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "whatsapp-in");
				t.setDaemon(true);
				return t;
			});
		}
	}

	@PreDestroy
	void stop() {
		for (ExecutorService e : lanes) {
			e.shutdown();
		}
	}

	/** Meta's one-time check of the webhook address. */
	@GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
	public ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
			@RequestParam(name = "hub.verify_token", required = false) String token,
			@RequestParam(name = "hub.challenge", required = false) String challenge) {
		if (!settings.receivingEnabled()) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
		}
		if ("subscribe".equals(mode) && token != null && constantTimeEquals(token, settings.verifyToken())
				&& challenge != null) {
			return ResponseEntity.ok(challenge);
		}
		return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
	}

	@PostMapping
	public ResponseEntity<Void> receive(@RequestBody(required = false) byte[] body,
			@RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) {
		if (!settings.receivingEnabled()) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
		}
		if (body == null || !validSignature(body, signature)) {
			log.warn("WhatsApp webhook call with a missing or wrong signature was refused");
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		JsonNode root;
		try {
			root = json.readTree(body);
		} catch (RuntimeException e) {
			return ResponseEntity.badRequest().build();
		}
		for (JsonNode entry : root.path("entry")) {
			for (JsonNode change : entry.path("changes")) {
				JsonNode value = change.path("value");
				// only messages sent to our own number
				String toId = value.path("metadata").path("phone_number_id").asString("");
				if (!toId.isEmpty() && !toId.equals(settings.phoneNumberId())) {
					continue;
				}
				for (JsonNode m : value.path("messages")) {
					String from = m.path("from").asString("");
					String id = m.path("id").asString("");
					if (from.isEmpty() || id.isEmpty()) {
						continue;
					}
					lanes[Math.floorMod(from.hashCode(), LANES)].submit(() -> handle(from, id, m));
				}
			}
		}
		return ResponseEntity.ok().build();
	}

	private void handle(String from, String messageId, JsonNode m) {
		try {
			if (inbound.existsById(messageId)) {
				return; // already handled (Meta delivered it again)
			}
			inbound.save(new WaInbound(messageId));
			String phone = LoginIdentifiers.normalizeMobile("+" + from);
			WaSession s = sessions.findById(phone).orElseGet(() -> new WaSession(phone));
			s.setLastInboundAt(LocalDateTime.now());
			sessions.save(s);

			Incoming in = incoming(m);
			List<WaMessage> replies = in == null
					? List.of(WaMessage.text("Sorry, I can only read text messages and taps on buttons for now."))
					: assistant.handle(phone, in);
			for (WaMessage r : replies) {
				client.send(phone, r); // the same number form the outbox uses
			}
		} catch (WaCloudClient.SendException e) {
			log.warn("WhatsApp reply to {} not sent: {}", mask(from), e.getMessage());
		} catch (RuntimeException e) {
			log.warn("WhatsApp message {} from {} could not be handled: {}", messageId, mask(from), e.toString());
		}
	}

	/** The person's message as the assistant understands it: typed text, or the id of what they tapped. */
	private static Incoming incoming(JsonNode m) {
		return switch (m.path("type").asString("")) {
			case "text" -> new Incoming(m.path("text").path("body").asString(""), null);
			case "interactive" -> {
				JsonNode i = m.path("interactive");
				String id = i.path("button_reply").path("id").asString(i.path("list_reply").path("id").asString(""));
				yield id.isEmpty() ? null : new Incoming(null, id);
			}
			// a quick-reply button on a template message (e.g. "Show summary")
			case "button" -> {
				String payload = m.path("button").path("payload").asString("");
				yield payload.isEmpty() ? new Incoming(m.path("button").path("text").asString(""), null)
						: new Incoming(null, payload);
			}
			default -> null;
		};
	}

	private boolean validSignature(byte[] body, String header) {
		if (header == null || !header.startsWith("sha256=")) {
			return false;
		}
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(settings.appSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			byte[] expected = mac.doFinal(body);
			byte[] given = HexFormat.of().parseHex(header.substring(7).trim().toLowerCase());
			return MessageDigest.isEqual(expected, given);
		} catch (Exception e) {
			return false;
		}
	}

	private static boolean constantTimeEquals(String a, String b) {
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}

	private static String mask(String phone) {
		return phone.length() <= 4 ? "****" : "…" + phone.substring(phone.length() - 4);
	}

	/** Forget handled message ids after a week (Meta only redelivers within a short time). */
	@Scheduled(cron = "0 30 3 * * *")
	public void cleanUp() {
		inbound.deleteOlderThan(LocalDateTime.now().minusDays(7));
	}
}
