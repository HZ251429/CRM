package com.hz.crm.notification;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.hz.crm.notification.Notification.Type;
import com.hz.crm.user.AppUser;
import com.hz.crm.user.AppUserRepository;
import com.hz.crm.user.Role;

/** Tells a person's leads (their manager, that manager's manager, ... and the admin) what they did. */
@Service
public class NotificationService {

	private final NotificationRepository notifications;
	private final AppUserRepository users;

	public NotificationService(NotificationRepository notifications, AppUserRepository users) {
		this.notifications = notifications;
		this.users = users;
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
			Notification n = new Notification();
			n.setTenantId(tenantId);
			n.setRecipientId(lead);
			n.setActorId(actorId);
			n.setType(type);
			n.setMessage(message.length() > 300 ? message.substring(0, 297) + "..." : message);
			n.setEntityId(entityId);
			batch.add(n);
		}
		notifications.saveAll(batch);
	}
}
