package com.hz.crm.notification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.hz.crm.security.AccessService;
import com.hz.crm.security.CrmUserDetails;

/** The current user's notifications. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

	private final NotificationRepository notifications;
	private final AccessService access;

	public NotificationController(NotificationRepository notifications, AccessService access) {
		this.notifications = notifications;
		this.access = access;
	}

	@GetMapping
	public Map<String, Object> list(@RequestParam(defaultValue = "30") int limit) {
		CrmUserDetails me = access.current();
		List<Notification> items = notifications.findByRecipientIdOrderByCreatedAtDescIdDesc(me.getId(),
				PageRequest.of(0, Math.min(Math.max(limit, 1), 100)));
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("unread", notifications.countByRecipientIdAndReadFalse(me.getId()));
		r.put("items", items);
		return r;
	}

	@PostMapping("/{id}/read")
	public ResponseEntity<Void> read(@PathVariable Long id) {
		Long me = access.current().getId();
		Notification n = notifications.findById(id).filter(x -> me.equals(x.getRecipientId()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		if (!n.isRead()) {
			n.setRead(true);
			notifications.save(n);
		}
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/read-all")
	public ResponseEntity<Void> readAll() {
		notifications.markAllRead(access.current().getId());
		return ResponseEntity.noContent().build();
	}
}
