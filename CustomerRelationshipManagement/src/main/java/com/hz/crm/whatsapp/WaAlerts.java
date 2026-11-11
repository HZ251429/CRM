package com.hz.crm.whatsapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.hz.crm.notification.Notification;
import com.hz.crm.notification.NotificationService;

/**
 * Passes the CRM's notifications (the bell) on to WhatsApp, for people who linked a number and want that kind of
 * alert. Messages go to the {@link WaOutbox}; a failure here never stops the action that caused the notification.
 */
@Service
public class WaAlerts {

	/** WhatsApp template names: business-started messages must use templates approved by Meta */
	public static final String TEMPLATE_TEAM_UPDATE = "crm_team_update";
	public static final String TEMPLATE_TASK_ASSIGNED = "crm_task_assigned";
	public static final String TEMPLATE_DAILY_SUMMARY = "crm_daily_summary";
	public static final String TEMPLATE_NEW_ENQUIRY = "crm_new_enquiry";
	public static final String TEMPLATE_FOLLOW_UP = "crm_follow_up_reminder";

	private static final Logger log = LoggerFactory.getLogger(WaAlerts.class);

	private final WaLinkRepository links;
	private final WaOutboxRepository outbox;

	public WaAlerts(WaLinkRepository links, WaOutboxRepository outbox) {
		this.links = links;
		this.outbox = outbox;
	}

	@EventListener
	public void onNotifications(NotificationService.Created event) {
		for (Notification n : event.notifications()) {
			try {
				links.findByUserId(n.getRecipientId()).ifPresent(link -> {
					// work handed to you (a task or an enquiry) follows the "tasks assigned to me" switch
					boolean enquiry = n.getType() == Notification.Type.ENQUIRY_ASSIGNED;
					boolean followUp = n.getType() == Notification.Type.FOLLOW_UP_DUE;
					boolean task = enquiry || followUp || n.getType() == Notification.Type.TASK_ASSIGNED;
					if (task ? link.isAlertsTasks() : link.isAlertsTeam()) {
						queue(link, WaOutbox.Kind.ALERT,
								followUp ? TEMPLATE_FOLLOW_UP : enquiry ? TEMPLATE_NEW_ENQUIRY
										: task ? TEMPLATE_TASK_ASSIGNED : TEMPLATE_TEAM_UPDATE,
								(followUp ? "⏰ " : enquiry ? "📥 " : task ? "🎯 " : "🔔 ") + n.getMessage());
					}
				});
			} catch (RuntimeException e) {
				log.warn("WhatsApp alert for notification {} not queued: {}", n.getId(), e.getMessage());
			}
		}
	}

	public WaOutbox queue(WaLink link, WaOutbox.Kind kind, String template, String text) {
		WaOutbox m = new WaOutbox();
		m.setPhone(link.getPhone());
		m.setUserId(link.getUserId());
		m.setKind(kind);
		m.setTemplate(template);
		m.setText(text.length() > 4096 ? text.substring(0, 4095) + "…" : text);
		return outbox.save(m);
	}
}
