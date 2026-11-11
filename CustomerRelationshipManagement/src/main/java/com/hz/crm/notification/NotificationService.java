package com.hz.crm.notification;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.hz.crm.notification.Notification.Type;
import com.hz.crm.user.AppUser;
import com.hz.crm.user.AppUserRepository;
import com.hz.crm.user.Role;

/** Tells a person's leads (their manager, that manager's manager, ... and the admin) what they did. */
@Service
public class NotificationService {

	/** Published after notifications are saved, so other channels (WhatsApp) can pass them on. */
	public record Created(List<Notification> notifications) {
	}

	private final NotificationRepository notifications;
	private final AppUserRepository users;
	private final ApplicationEventPublisher events;

	public NotificationService(NotificationRepository notifications, AppUserRepository users,
			ApplicationEventPublisher events) {
		this.notifications = notifications;
		this.users = users;
		this.events = events;
	}

	/** One notification for one person, e.g. "a task was assigned to you". */
	public void notifyUser(Long tenantId, Long recipientId, Long actorId, Type type, String message, Long entityId) {
		if (recipientId == null || recipientId.equals(actorId)) {
			return;
		}
		Notification n = build(tenantId, recipientId, actorId, type, message, entityId);
		notifications.save(n);
		events.publishEvent(new Created(List.of(n)));
	}

	/** A reminder from the system to a person about their own work (e.g. a follow-up that is due). */
	public void remind(Long tenantId, Long userId, Type type, String message, Long entityId) {
		Notification n = build(tenantId, userId, userId, type, message, entityId);
		notifications.save(n);
		events.publishEvent(new Created(List.of(n)));
	}

	private static Notification build(Long tenantId, Long recipientId, Long actorId, Type type, String message,
			Long entityId) {
		Notification n = new Notification();
		n.setTenantId(tenantId);
		n.setRecipientId(recipientId);
		n.setActorId(actorId);
		n.setType(type);
		n.setMessage(message.length() > 300 ? message.substring(0, 297) + "..." : message);
		n.setEntityId(entityId);
		return n;
	}

	/** Everyone above the user in the reporting line; the admin leads everyone. */
	public Set<Long> leadsOf(Long userId, Long tenantId) {
		List<AppUser> all = users.findByTenantId(tenantId);
		Map<Long, AppUser> byId = all.stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));
		Set<Long> leads = new HashSet<>();
		AppUser u = byId.get(userId);
		while (u != null && u.getManagerId() != null && leads.add(u.getManagerId())) {
			u = byId.get(u.getManagerId());
		}
		all.stream().filter(x -> x.getRole() == Role.TENANT_ADMIN && x.isActive()).forEach(x -> leads.add(x.getId()));
		leads.remove(userId);
		return leads;
	}

	public void notifyLeads(Long tenantId, Long actorId, Type type, String message, Long entityId) {
		List<Notification> batch = new ArrayList<>();
		for (Long lead : leadsOf(actorId, tenantId)) {
			batch.add(build(tenantId, lead, actorId, type, message, entityId));
		}
		notifications.saveAll(batch);
		if (!batch.isEmpty()) {
			events.publishEvent(new Created(batch));
		}
	}
}
