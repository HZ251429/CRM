package com.hz.crm.whatsapp;

import java.time.LocalDate;
import java.time.LocalTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hz.crm.user.AppUser;
import com.hz.crm.user.AppUserRepository;
import com.hz.crm.user.Role;

/**
 * Sends each linked person their daily summary at the hour they chose (default 7 pm, server time zone), once a day.
 * Runs every 10 minutes so a summary still goes out if the server was briefly down on the hour.
 */
@Component
public class WaSummaryJob {

	private static final Logger log = LoggerFactory.getLogger(WaSummaryJob.class);

	private final WaLinkRepository links;
	private final AppUserRepository users;
	private final WaAssistant assistant;
	private final WaAlerts alerts;

	public WaSummaryJob(WaLinkRepository links, AppUserRepository users, WaAssistant assistant, WaAlerts alerts) {
		this.links = links;
		this.users = users;
		this.assistant = assistant;
		this.alerts = alerts;
	}

	@Scheduled(cron = "0 */10 * * * *")
	public void run() {
		LocalDate today = LocalDate.now();
		int hour = LocalTime.now().getHour();
		for (WaLink link : links.findAll()) {
			if (!link.isSummaryEnabled() || link.getSummaryHour() > hour || today.equals(link.getLastSummaryOn())) {
				continue;
			}
			try {
				send(link);
			} catch (RuntimeException e) {
				log.warn("Daily summary for user {} not sent: {}", link.getUserId(), e.getMessage());
			}
		}
	}

	/** Queues the summary now and records that today's summary went out. Returns false if the user cannot get one. */
	public boolean send(WaLink link) {
		AppUser u = users.findById(link.getUserId()).orElse(null);
		if (u == null || !u.isActive() || u.getRole() == Role.SUPER_ADMIN) {
			return false;
		}
		alerts.queue(link, WaOutbox.Kind.SUMMARY, WaAlerts.TEMPLATE_DAILY_SUMMARY, assistant.summaryFor(u));
		link.setLastSummaryOn(LocalDate.now());
		links.save(link);
		return true;
	}
}
