package com.hz.crm.activity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.hz.crm.customer.CustomerRepository;
import com.hz.crm.notification.Notification;
import com.hz.crm.notification.NotificationService;
import com.hz.crm.security.AccessService;
import com.hz.crm.security.AccessService.Scope;

import jakarta.validation.constraints.Size;

/**
 * Follow-ups: the activities someone set a follow-up time on. A person sees their own (a lead can also look at their
 * team's), marks them done or moves them. A reminder goes to the bell (and WhatsApp, if linked) once, at the
 * follow-up time or the chosen time before it.
 */
@RestController
@RequestMapping("/api/followups")
public class FollowUpController {

	/** done = true marks it done, false reopens it; followUpAt moves it (and sends a new reminder) */
	public record Change(Boolean done, LocalDateTime followUpAt, @Size(max = 500) String note, Integer remindMinutes) {
	}

	/** reminder: at the time, 15 minutes, 1 hour or 1 day before */
	static final Set<Integer> REMIND_CHOICES = Set.of(0, 15, 60, 1440);
	static final String REMIND_ERROR = "Remind me: choose at the time, 15 minutes, 1 hour or 1 day before";

	private static final Logger log =LoggerFactory.getLogger(FollowUpController.class);
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.of("en", "IN"));

	private final ActivityRepository activities;
	private final ActivitySearch search;
	private final CustomerRepository customers;
	private final AccessService access;
	private final NotificationService notifications;

	public FollowUpController(ActivityRepository activities, ActivitySearch search, CustomerRepository customers,
			AccessService access, NotificationService notifications) {
		this.activities = activities;
		this.search = search;
		this.customers = customers;
		this.access = access;
		this.notifications = notifications;
	}

	/**
	 * @param who  "me" (default) or "team" (everyone the user can see, themselves included)
	 * @param open true = still to do (soonest first), false = done (latest 100)
	 */
	@GetMapping
	public List<ActivitySearch.Row> list(@RequestParam(defaultValue = "me") String who,
			@RequestParam(defaultValue = "true") boolean open) {
		Scope s = access.scope();
		Collection<Long> owners = "team".equals(who) ? s.ownerIds() : Set.of(s.me().getId());
		List<Activity> found = activities.findFollowUps(s.tenantId(), owners, open, PageRequest.of(0, open ? 500 : 100));
		if (!open) {
			found = found.reversed();
		}
		return search.rows(found);
	}

	/** Numbers for the badge: overdue and due today (open, the user's own). */
	@GetMapping("/summary")
	public Map<String, Long> summary() {
		Scope s = access.scope();
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime endOfDay = LocalDate.now().plusDays(1).atStartOfDay();
		List<Activity> open = activities.findFollowUps(s.tenantId(), Set.of(s.me().getId()), true, PageRequest.of(0, 1000));
		return Map.of("overdue", open.stream().filter(a -> a.getFollowUpAt().isBefore(now)).count(),
				"today", open.stream().filter(a -> !a.getFollowUpAt().isBefore(now) && a.getFollowUpAt().isBefore(endOfDay)).count());
	}

	/** Mark done / reopen / move. Only the person who logged the activity can change its follow-up. */
	@PutMapping("/{id}")
	public ActivitySearch.Row change(@PathVariable Long id, @RequestBody Change req) {
		Scope s = access.scope();
		Activity a = activities.findById(id)
				.filter(x -> s.tenantId().equals(x.getTenantId()) && s.me().getId().equals(x.getOwnerId()) && x.getFollowUpAt() != null)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Follow-up not found"));
		if (req.followUpAt() != null && !req.followUpAt().equals(a.getFollowUpAt())) {
			if (req.followUpAt().isBefore(LocalDateTime.now().minusMinutes(1))) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick a follow-up date and time in the future");
			}
			a.setFollowUpAt(req.followUpAt());
			a.setFollowUpRemindedAt(null);
			a.setFollowUpDoneAt(null);
		}
		if (req.remindMinutes() != null && !REMIND_CHOICES.contains(req.remindMinutes())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, REMIND_ERROR);
		}
		if (req.remindMinutes() != null && !req.remindMinutes().equals(a.getFollowUpRemindMinutes())) {
			a.setFollowUpRemindMinutes(req.remindMinutes());
			a.setFollowUpRemindedAt(null);
		}
		if (req.note() != null) {
			a.setFollowUpNote(req.note().isBlank() ? null : req.note().trim());
		}
		if (req.done() != null) {
			a.setFollowUpDoneAt(req.done() ? LocalDateTime.now() : null);
		}
		return search.rows(List.of(activities.save(a))).get(0);
	}

	/** Every minute: send the reminders that are due (once each). */
	@Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
	public void sendReminders() {
		LocalDateTime now = LocalDateTime.now();
		for (Activity a : activities.findRemindersDueBy(now.plusDays(1))) {
			int before = a.getFollowUpRemindMinutes() == null ? 0 : a.getFollowUpRemindMinutes();
			if (a.getFollowUpAt().minusMinutes(before).isAfter(now)) {
				continue; // not yet
			}
			try {
				String about = a.getCustomerId() != null
						? customers.findById(a.getCustomerId()).map(c -> c.getName()).orElse("a customer") : "your task";
				String msg = (a.getFollowUpAt().isBefore(now) ? "Follow-up due now" : "Follow-up at " + a.getFollowUpAt().format(WHEN))
						+ " with " + about + ": " + (a.getFollowUpNote() != null ? a.getFollowUpNote() : a.getSubject());
				notifications.remind(a.getTenantId(), a.getOwnerId(), Notification.Type.FOLLOW_UP_DUE, msg, a.getId());
				a.setFollowUpRemindedAt(now);
				activities.save(a);
			} catch (RuntimeException e) {
				log.warn("Follow-up reminder for activity {} not sent: {}", a.getId(), e.getMessage());
			}
		}
	}
}
