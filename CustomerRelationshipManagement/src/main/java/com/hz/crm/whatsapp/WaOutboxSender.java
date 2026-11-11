package com.hz.crm.whatsapp;

import static com.hz.crm.whatsapp.WaMessage.button;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Delivers the outbox (alerts and daily summaries) through Meta, when the WhatsApp connection is configured.
 * <ul>
 * <li>Within 24 hours of the person's last WhatsApp message, the full text goes out as a normal message.</li>
 * <li>Otherwise WhatsApp only allows an approved template: alerts use crm_task_assigned / crm_team_update with the
 * alert as {{1}}; the summary uses crm_daily_summary with the date as {{1}} and a "Show summary" button that makes
 * the assistant send the full, current summary.</li>
 * <li>A failed send is tried again on the next runs, up to 5 times. Messages older than a day are skipped rather than
 * sent late (for example everything that piled up in test mode before the connection was set up).</li>
 * </ul>
 */
@Component
public class WaOutboxSender {

	static final int MAX_ATTEMPTS = 5;
	private static final Duration WINDOW = Duration.ofHours(24);
	private static final Duration TOO_OLD = Duration.ofHours(24);
	/** pause after the 1st, 2nd, 3rd and 4th failed attempt */
	private static final Duration[] RETRY_AFTER = { Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
			Duration.ofMinutes(60) };
	private static final Logger log = LoggerFactory.getLogger(WaOutboxSender.class);

	private final WaCloudSettings settings;
	private final WaCloudClient client;
	private final WaOutboxRepository outbox;
	private final WaSessionRepository sessions;

	public WaOutboxSender(WaCloudSettings settings, WaCloudClient client, WaOutboxRepository outbox,
			WaSessionRepository sessions) {
		this.settings = settings;
		this.client = client;
		this.outbox = outbox;
		this.sessions = sessions;
	}

	@Scheduled(fixedDelay = 10_000, initialDelay = 15_000)
	public void run() {
		if (!settings.sendingEnabled()) {
			return; // test mode: messages stay in the outbox for the test chat
		}
		List<WaOutbox> waiting = outbox.findWaiting(MAX_ATTEMPTS, LocalDateTime.now(), PageRequest.of(0, 50));
		for (WaOutbox m : waiting) {
			deliver(m);
		}
	}

	void deliver(WaOutbox m) {
		LocalDateTime now = LocalDateTime.now();
		if (m.getCreatedAt().isBefore(now.minus(TOO_OLD))) {
			m.setAttempts(MAX_ATTEMPTS);
			m.setLastError("Skipped: more than a day old when WhatsApp sending was available");
			outbox.save(m);
			return;
		}
		try {
			boolean inWindow = sessions.findById(m.getPhone()).map(WaSession::getLastInboundAt)
					.filter(t -> t.isAfter(now.minus(WINDOW))).isPresent();
			if (inWindow) {
				client.send(m.getPhone(), m.getKind() == WaOutbox.Kind.SUMMARY
						? WaMessage.buttons(m.getText(), button("m:log", "Log activity"), button("m:menu", "Menu"))
						: WaMessage.text(m.getText()));
			} else if (m.getKind() == WaOutbox.Kind.SUMMARY) {
				client.sendTemplate(m.getPhone(), m.getTemplate(),
						List.of(m.getCreatedAt().format(DateTimeFormatter.ofPattern("d MMM", Locale.of("en", "IN")))),
						List.of("m:summary"));
			} else {
				client.sendTemplate(m.getPhone(), m.getTemplate(), List.of(stripIcon(m.getText())), List.of());
			}
			m.setSentAt(LocalDateTime.now());
			m.setLastError(null);
		} catch (WaCloudClient.SendException e) {
			m.setAttempts(m.getAttempts() + 1);
			m.setLastError(e.getMessage());
			// wait longer after each failure, so a short Meta outage does not use up all attempts
			m.setNextTryAt(LocalDateTime.now().plus(RETRY_AFTER[Math.min(m.getAttempts(), RETRY_AFTER.length) - 1]));
			log.warn("WhatsApp outbox message {} not sent (attempt {}): {}", m.getId(), m.getAttempts(), e.getMessage());
		}
		outbox.save(m);
	}

	/** the templates start with their own icon, so the alert's leading 🎯 / 🔔 is left out of {{1}} */
	private static String stripIcon(String text) {
		return text.replaceFirst("^[^\\p{L}\\p{N}\"(]+", "").trim();
	}
}
