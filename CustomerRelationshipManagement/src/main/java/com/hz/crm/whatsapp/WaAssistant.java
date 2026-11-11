package com.hz.crm.whatsapp;

import static com.hz.crm.whatsapp.WaMessage.button;
import static com.hz.crm.whatsapp.WaMessage.row;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.hz.crm.activity.Activity;
import com.hz.crm.activity.ActivityController;
import com.hz.crm.customer.Customer;
import com.hz.crm.customer.CustomerController;
import com.hz.crm.product.Product;
import com.hz.crm.product.ProductController;
import com.hz.crm.security.CrmUserDetails;
import com.hz.crm.security.CrmUserDetailsService;
import com.hz.crm.task.SalesTaskController;
import com.hz.crm.task.SalesTaskController.TaskView;
import com.hz.crm.task.TaskProgressService.Status;
import com.hz.crm.user.AppUser;
import com.hz.crm.user.AppUserRepository;
import com.hz.crm.user.Role;
import com.hz.crm.whatsapp.WaMessage.Incoming;
import com.hz.crm.whatsapp.WaMessage.Row;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

/**
 * The WhatsApp assistant: a person writes to the CRM's WhatsApp number and works with the CRM by chat. It runs as
 * the CRM user the number is linked to and calls the same code as the web app, so permissions, balance limits and
 * stock rules are identical. Multi-step conversations (logging an activity, adding a customer) keep their progress
 * in {@link WaSession}; an unfinished one is dropped after 30 minutes.
 *
 * Works the same for the test chat in the web app and, later, for the WhatsApp Cloud API webhook.
 */
@Service
public class WaAssistant {

	private static final Duration SESSION_TIMEOUT = Duration.ofMinutes(30);
	private static final Map<String, String> TYPES = new LinkedHashMap<>();
	static {
		TYPES.put("MEETING", "🤝 Meeting");
		TYPES.put("CALL", "📞 Call");
		TYPES.put("VISIT", "📍 Visit");
		TYPES.put("EMAIL", "✉️ Email");
		TYPES.put("NOTE", "📝 Note");
	}

	private final WaLinkService linkService;
	private final WaSessionRepository sessions;
	private final AppUserRepository users;
	private final CrmUserDetailsService userDetails;
	private final SalesTaskController tasks;
	private final ProductController products;
	private final CustomerController customers;
	private final ActivityController activities;
	private final Validator validator;
	private final com.hz.crm.dashboard.DashboardController dashboard;
	private final com.hz.crm.activity.ActivityRepository activityRepo;
	private final com.hz.crm.security.AccessService access;
	private final com.hz.crm.activity.FollowUpController followUps;

	public WaAssistant(WaLinkService linkService, WaSessionRepository sessions, AppUserRepository users,
			CrmUserDetailsService userDetails, SalesTaskController tasks, ProductController products,
			CustomerController customers, ActivityController activities, Validator validator,
			com.hz.crm.dashboard.DashboardController dashboard, com.hz.crm.activity.ActivityRepository activityRepo,
			com.hz.crm.security.AccessService access, com.hz.crm.activity.FollowUpController followUps) {
		this.followUps = followUps;
		this.dashboard = dashboard;
		this.activityRepo = activityRepo;
		this.access = access;
		this.linkService = linkService;
		this.sessions = sessions;
		this.users = users;
		this.userDetails = userDetails;
		this.tasks = tasks;
		this.products = products;
		this.customers = customers;
		this.activities = activities;
		this.validator = validator;
	}

	// ======================================================================== entry point

	/** Handles one incoming message from a WhatsApp number (digits with country code) and returns the replies. */
	public List<WaMessage> handle(String phone, Incoming in) {
		String text = in.text() == null ? "" : in.text().trim();
		String reply = in.replyId();

		Optional<WaLink> link = linkService.linkOfPhone(phone);
		// a link code: any code-shaped text from a number that is not linked yet; from a linked number only when it is
		// clearly a code (LINK ... or ABCD-EFGH), so an ordinary 8-letter answer is never taken for one
		String code = reply == null ? WaLinkService.codeIn(text) : null;
		if (code != null && (link.isEmpty() || WaLinkService.isExplicitCode(text))) {
			return linkReply(phone, code);
		}
		Optional<AppUser> user = link.flatMap(l -> users.findById(l.getUserId()));
		if (user.isEmpty()) {
			return List.of(WaMessage.text("👋 Hi! This WhatsApp number is not linked to a CRM account yet.\n\n"
					+ "To link it: sign in to the CRM website, open *WhatsApp*, tap *Get link code* "
					+ "and send that code here."));
		}
		AppUser u = user.get();
		if (u.getRole() == Role.SUPER_ADMIN) {
			return List.of(WaMessage.text("The WhatsApp assistant is for company users. Please use the website."));
		}
		CrmUserDetails details;
		try {
			details = (CrmUserDetails) userDetails.loadUserByUsername(u.getUsername());
		} catch (RuntimeException e) {
			return List.of(WaMessage.text("Your account could not be found. Please contact your admin."));
		}
		if (!details.isEnabled()) {
			return List.of(WaMessage.text("Your account or your company is disabled. Please contact your admin."));
		}

		WaSession s = sessions.findById(phone).orElseGet(() -> new WaSession(phone));
		List<WaMessage> out = new ArrayList<>();
		if (!"IDLE".equals(s.getState()) && s.getUpdatedAt().isBefore(LocalDateTime.now().minus(SESSION_TIMEOUT))) {
			s.reset();
			out.add(WaMessage.text("⌛ Your unfinished entry was dropped after 30 minutes."));
		}
		try {
			out.addAll(asUser(details, () -> route(s, u, text, reply)));
		} catch (ResponseStatusException e) {
			out.add(WaMessage.text("⚠️ " + (e.getReason() == null ? "That did not work." : e.getReason())));
		}
		sessions.save(s);
		return out;
	}

	private List<WaMessage> linkReply(String phone, String code) {
		return switch (linkService.link(phone, code)) {
			case LINKED -> {
				AppUser u = linkService.linkOfPhone(phone).flatMap(l -> users.findById(l.getUserId())).orElseThrow();
				yield List.of(WaMessage.text("✅ Linked! This number now signs you in as *" + name(u) + "*."), menu(u));
			}
			case ALREADY_LINKED_TO_YOU -> List.of(WaMessage.text("This number is already linked to your account."));
			case NUMBER_TAKEN -> List.of(WaMessage.text(
					"This number is linked to another CRM account. Unlink it there first (WhatsApp page on the website)."));
			case BLOCKED -> List.of(WaMessage.text("Too many wrong codes. Please wait 15 minutes and try again."));
			case WRONG_OR_EXPIRED -> List.of(WaMessage.text(
					"That code is wrong or has expired. Get a new one on the website (codes last 10 minutes)."));
		};
	}

	/** Runs CRM code as the linked user, exactly as if they were signed in on the website. */
	private <T> T asUser(CrmUserDetails details, Supplier<T> work) {
		SecurityContext previous = SecurityContextHolder.getContext();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
		SecurityContextHolder.setContext(ctx);
		try {
			return work.get();
		} finally {
			SecurityContextHolder.setContext(previous);
		}
	}

	// ======================================================================== routing

	private List<WaMessage> route(WaSession s, AppUser u, String text, String reply) {
		String cmd = text.toLowerCase(Locale.ROOT);
		if (reply == null) {
			switch (cmd) {
				case "hi", "hello", "hey", "menu", "start", "help", "?" -> {
					s.reset();
					return List.of(menu(u));
				}
				case "cancel", "stop", "exit", "quit" -> {
					boolean wasBusy = !"IDLE".equals(s.getState());
					s.reset();
					return List.of(WaMessage.text(wasBusy ? "Cancelled. Nothing was saved." : "Nothing to cancel."), menu(u));
				}
				case "balance", "my balance" -> {
					s.reset();
					return balance(u);
				}
				case "summary", "today", "report" -> {
					s.reset();
					return List.of(WaMessage.buttons(summaryText(u), button("m:log", "Log activity"), button("m:menu", "Menu")));
				}
				case "tasks", "my tasks" -> {
					s.reset();
					return myTasks(u);
				}
				case "followups", "follow-ups", "follow ups", "followup", "follow-up", "my followups", "my follow-ups" -> {
					s.reset();
					return myFollowUps();
				}
				case "log", "activity", "log activity", "meeting", "call" -> {
					return startLog(s, null);
				}
				case "sale", "sell", "record sale", "new sale" -> {
					return startSale(s, u, null);
				}
				case "customer", "new customer", "add customer" -> {
					return startCustomer(s);
				}
				default -> {
				}
			}
		} else {
			switch (reply) {
				case "m:menu" -> {
					s.reset();
					return List.of(menu(u));
				}
				case "m:log" -> {
					return startLog(s, null);
				}
				case "m:sale" -> {
					return startSale(s, u, null);
				}
				case "m:balance" -> {
					s.reset();
					return balance(u);
				}
				case "m:summary" -> {
					s.reset();
					return List.of(WaMessage.buttons(summaryText(u), button("m:log", "Log activity"), button("m:menu", "Menu")));
				}
				case "m:tasks" -> {
					s.reset();
					return myTasks(u);
				}
				case "m:followups" -> {
					s.reset();
					return myFollowUps();
				}
				case "m:customer" -> {
					return startCustomer(s);
				}
				case "m:help" -> {
					s.reset();
					return List.of(help());
				}
				case "cancel" -> {
					s.reset();
					return List.of(WaMessage.text("Cancelled. Nothing was saved."), menu(u));
				}
				default -> {
					// buttons shown after adding a customer: start straight away with that customer
					if (reply.startsWith("fu:done:")) {
						s.reset();
						followUps.change(Long.valueOf(reply.substring(8)), new com.hz.crm.activity.FollowUpController.Change(true, null, null, null));
						List<WaMessage> out = new ArrayList<>();
						out.add(WaMessage.text("✅ Follow-up marked done."));
						out.addAll(myFollowUps());
						return out;
					}
					if (reply.startsWith("log:c:") || reply.startsWith("sale:c:")) {
						Customer c = customers.get(Long.valueOf(reply.substring(reply.lastIndexOf(':') + 1))).getBody();
						if (c != null) {
							return reply.startsWith("log:") ? startLog(s, c) : startSale(s, u, c);
						}
					}
				}
			}
		}
		String state = s.getState();
		if (state.startsWith("LOG_")) {
			return logStep(s, u, text, reply);
		}
		if (state.startsWith("SALE_")) {
			return saleStep(s, u, text, reply);
		}
		if (state.startsWith("CUST_")) {
			return customerStep(s, u, text, reply);
		}
		return List.of(WaMessage.text("Sorry, I did not understand that."), menu(u));
	}

	private WaMessage menu(AppUser u) {
		List<Row> rows = new ArrayList<>();
		rows.add(row("m:log", "📝 Log activity", "A meeting (in person or online), call or other"));
		rows.add(row("m:sale", "💰 Record sale", "Units sold to a customer, and the payment"));
		rows.add(row("m:balance", "📦 My balance", u.getRole() == Role.TENANT_ADMIN ? "Units still unsold, per product"
				: "Units you can still sell, per product"));
		rows.add(row("m:tasks", "🎯 My tasks", "Open tasks and their progress"));
		rows.add(row("m:followups", "⏰ My follow-ups", "What to follow up on, soonest first"));
		rows.add(row("m:summary", "📊 Today's summary", "Your numbers for today"));
		rows.add(row("m:customer", "👤 Add customer", "Save a new customer"));
		rows.add(row("m:help", "❓ Help", "Commands you can type"));
		return WaMessage.list("Hi " + firstName(u) + "! What would you like to do?", "Menu", rows);
	}

	private static WaMessage help() {
		return WaMessage.text("""
				You can tap *Menu* or type:
				• *log* – log a meeting, call or other activity
				• *sale* – record a sale
				• *balance* – units you can still sell
				• *tasks* – your open tasks
				• *followups* – your follow-ups
				• *summary* – today's numbers
				• *customer* – add a customer
				• *cancel* – stop what you are doing
				• *menu* – show the menu""");
	}

	private static WaMessage backToMenu(String text) {
		return WaMessage.buttons(text, button("m:log", "Log another"), button("m:menu", "Menu"));
	}

	// ======================================================================== balance and tasks

	private List<TaskView> myTaskViews(AppUser u) {
		boolean admin = u.getRole() == Role.TENANT_ADMIN;
		return tasks.list().stream()
				.filter(t -> admin ? u.getId().equals(t.assignedById()) : u.getId().equals(t.assignedToId()))
				.toList();
	}

	private List<WaMessage> balance(AppUser u) {
		List<TaskView> mine = myTaskViews(u);
		if (mine.isEmpty()) {
			return List.of(WaMessage.buttons(u.getRole() == Role.TENANT_ADMIN ? "You have not assigned any tasks yet."
					: "You have no tasks yet, so there is nothing to sell. Ask your lead to assign you a task.",
					button("m:menu", "Menu")));
		}
		Map<String, long[]> byProduct = new LinkedHashMap<>(); // product -> [own, team]
		Map<String, String> units = new LinkedHashMap<>();
		for (TaskView t : mine) {
			long[] v = byProduct.computeIfAbsent(t.productName(), k -> new long[2]);
			if (u.getRole() == Role.TENANT_ADMIN) {
				v[1] += t.teamBalance();
			} else {
				v[0] += t.ownBalance();
				v[1] += Math.max(0, t.teamBalance() - t.ownBalance());
			}
			units.put(t.productName(), t.unit());
		}
		StringBuilder sb = new StringBuilder("📦 *Your balance*\n");
		byProduct.forEach((p, v) -> {
			sb.append("\n*").append(p).append("*\n");
			if (u.getRole() != Role.TENANT_ADMIN) {
				sb.append("  You can sell: *").append(fmt(v[0])).append(' ').append(units.get(p)).append("*\n");
			}
			if (v[1] > 0 || u.getRole() == Role.TENANT_ADMIN) {
				sb.append("  With your team: ").append(fmt(v[1])).append(' ').append(units.get(p)).append(" unsold\n");
			}
		});
		return List.of(WaMessage.buttons(sb.toString().trim(), button("m:sale", "Record sale"), button("m:tasks", "My tasks"),
				button("m:menu", "Menu")));
	}

	private List<WaMessage> myTasks(AppUser u) {
		List<TaskView> open = myTaskViews(u).stream().filter(t -> t.status() != Status.COMPLETED)
				.sorted((a, b) -> a.createdAt().compareTo(b.createdAt())).toList();
		if (open.isEmpty()) {
			return List.of(WaMessage.buttons("🎉 No open tasks.", button("m:menu", "Menu")));
		}
		StringBuilder sb = new StringBuilder("🎯 *Open tasks* (" + open.size() + ")\n");
		for (TaskView t : open.stream().limit(10).toList()) {
			sb.append("\n*").append(t.title() == null ? t.productName() : t.title()).append("* · ").append(t.productName())
					.append("\n  ").append(fmt(t.soldUnits())).append(" of ").append(fmt(t.units())).append(' ')
					.append(t.unit()).append(" sold");
			if (u.getRole() != Role.TENANT_ADMIN) {
				sb.append(" · you can sell ").append(fmt(t.ownBalance()));
			} else {
				sb.append(" · ").append(t.assignedToName());
			}
		}
		if (open.size() > 10) {
			sb.append("\n\n…and ").append(open.size() - 10).append(" more on the website.");
		}
		return List.of(WaMessage.buttons(sb.toString(), button("m:sale", "Record sale"), button("m:balance", "My balance"),
				button("m:menu", "Menu")));
	}

	private static final java.time.format.DateTimeFormatter FU_WHEN =
			java.time.format.DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.of("en", "IN"));

	/** The open follow-ups, soonest first; a button marks the first one done. */
	private List<WaMessage> myFollowUps() {
		List<com.hz.crm.activity.ActivitySearch.Row> open = followUps.list("me", true);
		if (open.isEmpty()) {
			return List.of(WaMessage.buttons("🎉 No follow-ups to do.\n\nWhen you log an activity on the website you can tick *Follow-up needed*.",
					button("m:log", "Log activity"), button("m:menu", "Menu")));
		}
		LocalDateTime now = LocalDateTime.now();
		StringBuilder sb = new StringBuilder("⏰ *Your follow-ups* (" + open.size() + ")\n");
		int i = 0;
		for (com.hz.crm.activity.ActivitySearch.Row r : open.stream().limit(10).toList()) {
			sb.append("\n").append(++i).append(". ").append(r.followUpAt().isBefore(now) ? "🔴 " : "")
					.append("*").append(r.followUpAt().format(FU_WHEN)).append("*");
			String about = r.customerName() != null ? r.customerName() : r.taskTitle() != null ? "🎯 " + r.taskTitle() : null;
			if (about != null) {
				sb.append(" · ").append(about);
			}
			sb.append("\n   ").append(r.followUpNote() != null ? r.followUpNote() : r.subject());
		}
		if (open.size() > 10) {
			sb.append("\n\n…and ").append(open.size() - 10).append(" more on the website.");
		}
		return List.of(WaMessage.buttons(sb.toString(), button("fu:done:" + open.get(0).id(), "Done: no. 1"),
				button("m:log", "Log activity"), button("m:menu", "Menu")));
	}

	// ======================================================================== log an activity (meeting, call, other)

	private static final Map<String, String[]> KINDS = new LinkedHashMap<>(); // reply id -> [type, meetingMode, label]
	static {
		KINDS.put("t:MEETING_IN", new String[] { "MEETING", "IN_PERSON", "🤝 Meeting (in person)" });
		KINDS.put("t:MEETING_ON", new String[] { "MEETING", "ONLINE", "💻 Meeting (online)" });
		KINDS.put("t:CALL", new String[] { "CALL", null, "📞 Call" });
		KINDS.put("t:OTHER", new String[] { "OTHER", null, "🗂️ Other" });
	}

	/** A new activity; with a customer already chosen when started from "Log activity" after adding a customer. */
	private List<WaMessage> startLog(WaSession s, Customer preset) {
		s.reset();
		if (preset != null) {
			s.put("customerId", String.valueOf(preset.getId()));
			s.put("customerName", preset.getName());
		}
		s.setState("LOG_TYPE");
		return List.of(WaMessage.list("📝 *Log activity*" + (preset == null ? "" : " with " + s.get("customerName"))
				+ "\nWhat kind of activity?\n(Type *cancel* any time to stop.)", "Choose type",
				KINDS.entrySet().stream().map(e -> row(e.getKey(), e.getValue()[2], null)).toList()));
	}

	private List<WaMessage> logStep(WaSession s, AppUser u, String text, String reply) {
		switch (s.getState()) {
			case "LOG_TYPE" -> {
				String[] kind = reply == null ? null : KINDS.get(reply);
				if (kind == null) {
					return List.of(WaMessage.text("Please choose the type from the list."));
				}
				s.put("type", kind[0]);
				s.put("mode", kind[1]);
				s.put("kindLabel", kind[2]);
				if (s.get("customerId") != null) {
					s.setState("LOG_TITLE");
					return List.of(titlePrompt());
				}
				s.setState("LOG_ABOUT");
				return List.of(aboutPrompt());
			}
			case "LOG_ABOUT" -> {
				if ("about:customer".equals(reply)) {
					s.setState("LOG_CUSTOMER");
					return List.of(WaMessage.text("Which customer? Type part of their name, email or company."));
				}
				if ("about:task".equals(reply)) {
					List<TaskView> open = tasks.list().stream().filter(t -> t.status() != Status.COMPLETED)
							.sorted((a, b) -> b.createdAt().compareTo(a.createdAt())).limit(10).toList();
					if (open.isEmpty()) {
						return List.of(WaMessage.buttons("There are no open tasks to log against.", button("about:customer", "A customer"),
								button("cancel", "Cancel")));
					}
					s.setState("LOG_TASK");
					return List.of(WaMessage.list("Which task is it about?", "Choose task", open.stream()
							.map(t -> row("k:" + t.id(), t.title() == null ? t.productName() : t.title(),
									t.productName() + " · " + t.assignedToName()))
							.toList()));
				}
				return List.of(aboutPrompt());
			}
			case "LOG_CUSTOMER" -> {
				Customer c = pickCustomer(text, reply);
				if (c == null) {
					return searchCustomers(text);
				}
				s.put("customerId", String.valueOf(c.getId()));
				s.put("customerName", c.getName());
				s.setState("LOG_TITLE");
				return List.of(titlePrompt());
			}
			case "LOG_TASK" -> {
				if (reply == null || !reply.startsWith("k:")) {
					return List.of(WaMessage.text("Please choose the task from the list."));
				}
				Long id = Long.valueOf(reply.substring(2));
				TaskView t = tasks.list().stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
				if (t == null) {
					return List.of(WaMessage.text("That task is not available. Please choose from the list."));
				}
				s.put("taskId", String.valueOf(t.id()));
				s.put("taskName", t.title() == null ? t.productName() : t.title());
				s.setState("LOG_TITLE");
				return List.of(titlePrompt());
			}
			case "LOG_TITLE" -> {
				if (text.isBlank() || text.length() > 200) {
					return List.of(WaMessage.text("Please send a title of up to 200 characters."));
				}
				s.put("subject", text);
				s.setState("LOG_NOTES");
				return List.of(WaMessage.buttons("Any notes or next steps? Send them, or tap *Skip*.", button("skip", "Skip")));
			}
			case "LOG_NOTES" -> {
				if (!"skip".equals(reply) && !"skip".equalsIgnoreCase(text)) {
					if (text.length() > 2000) {
						return List.of(WaMessage.text("Notes can be up to 2000 characters. Please shorten them."));
					}
					s.put("notes", text);
				}
				s.setState("LOG_CONFIRM");
				StringBuilder sb = new StringBuilder("Please check:\n\n").append(s.get("kindLabel")).append(": *")
						.append(s.get("subject")).append("*\n");
				sb.append(s.get("customerId") != null ? "👤 " + s.get("customerName") : "🎯 Task: " + s.get("taskName"));
				if (s.get("notes") != null) {
					sb.append("\n🗒 ").append(s.get("notes"));
				}
				return List.of(WaMessage.buttons(sb.toString(), button("save:yes", "Save"), button("save:no", "Cancel")));
			}
			case "LOG_CONFIRM" -> {
				if ("save:no".equals(reply)) {
					s.reset();
					return List.of(WaMessage.text("Cancelled. Nothing was saved."), menu(u));
				}
				if (!"save:yes".equals(reply)) {
					return List.of(WaMessage.buttons("Save this activity?", button("save:yes", "Save"), button("save:no", "Cancel")));
				}
				Activity a = new Activity();
				a.setType(s.get("type"));
				a.setMeetingMode(s.get("mode"));
				a.setCustomerId(s.get("customerId") == null ? null : Long.valueOf(s.get("customerId")));
				a.setTaskId(s.get("taskId") == null ? null : Long.valueOf(s.get("taskId")));
				a.setSubject(s.get("subject"));
				a.setOutcome(s.get("notes"));
				a.setActivityTime(LocalDateTime.now());
				String problem = check(a);
				if (problem != null) {
					return List.of(WaMessage.text("⚠️ " + problem));
				}
				activities.create(a); // same rules as the website
				s.reset();
				return List.of(WaMessage.buttons("✅ Activity saved.", button("m:log", "Log another"), button("m:menu", "Menu")));
			}
			default -> {
				s.reset();
				return List.of(menu(u));
			}
		}
	}

	private static WaMessage aboutPrompt() {
		return WaMessage.buttons("Is it about a customer or a task?", button("about:customer", "A customer"),
				button("about:task", "A task"));
	}

	private static WaMessage titlePrompt() {
		return WaMessage.text("What was it about? Send a short title (e.g. _Price discussion for March order_).");
	}

	/** The customer the person tapped in a search result, or null (then the text is a new search). */
	private Customer pickCustomer(String text, String reply) {
		if (reply != null && reply.startsWith("c:")) {
			return customers.get(Long.valueOf(reply.substring(2))).getBody();
		}
		return null;
	}

	private List<WaMessage> searchCustomers(String text) {
		if (text.isBlank()) {
			return List.of(WaMessage.text("Type part of the customer's name, email or company."));
		}
		List<Customer> found = customers.list(text, null, 0, 10).content();
		if (found.isEmpty()) {
			return List.of(WaMessage.buttons("No customers match \"" + text + "\". Try another name, or add the customer first.",
					button("m:customer", "Add customer"), button("m:menu", "Menu")));
		}
		return List.of(WaMessage.list("Customers matching \"" + text + "\" (or type another name):", "Choose customer",
				found.stream().map(c -> row("c:" + c.getId(), c.getName(),
						c.getCompany() != null && !c.getCompany().isBlank() ? c.getCompany() : c.getPhone() != null ? c.getPhone() : c.getEmail())).toList()));
	}

	// ======================================================================== record a sale

	/** A new sale; with the customer already chosen when started right after adding a customer. */
	private List<WaMessage> startSale(WaSession s, AppUser u, Customer preset) {
		s.reset();
		if (preset == null) {
			s.setState("SALE_CUSTOMER");
			return List.of(WaMessage.text("💰 *Record sale*\nWhich customer? Type part of their name, email or company.\n"
					+ "(Type *cancel* any time to stop.)"));
		}
		s.put("customerId", String.valueOf(preset.getId()));
		s.put("customerName", preset.getName());
		return askProduct(s, u);
	}

	private List<WaMessage> askProduct(WaSession s, AppUser u) {
		List<Row> rows = saleProducts(u);
		if (rows.isEmpty()) {
			s.reset();
			return List.of(WaMessage.buttons(u.getRole() == Role.TENANT_ADMIN ? "There are no active products."
					: "You have no units left to sell on any task. Ask your lead to assign you a task.", button("m:menu", "Menu")));
		}
		s.setState("SALE_PRODUCT");
		return List.of(WaMessage.list("Customer: *" + s.get("customerName") + "*\nWhich product was sold?", "Choose product", rows));
	}

	private List<WaMessage> saleStep(WaSession s, AppUser u, String text, String reply) {
		switch (s.getState()) {
			case "SALE_CUSTOMER" -> {
				Customer c = pickCustomer(text, reply);
				if (c == null) {
					return searchCustomers(text);
				}
				s.put("customerId", String.valueOf(c.getId()));
				s.put("customerName", c.getName());
				return askProduct(s, u);
			}
			case "SALE_PRODUCT" -> {
				if (reply == null || !reply.startsWith("p:")) {
					return List.of(WaMessage.text("Please choose the product from the list."));
				}
				Long pid = Long.valueOf(reply.substring(2));
				Product p = products.list().stream().filter(x -> x.getId().equals(pid)).findFirst().orElse(null);
				if (p == null) {
					return List.of(WaMessage.text("That product is not available. Please choose from the list."));
				}
				s.put("productId", String.valueOf(p.getId()));
				s.put("productName", p.getName());
				s.put("unit", p.getUnit());
				Long max = u.getRole() == Role.TENANT_ADMIN ? null : ownBalance(u, p.getId());
				s.put("max", max == null ? null : String.valueOf(max));
				s.setState("SALE_UNITS");
				return List.of(WaMessage.text("How many " + p.getUnit() + " of " + p.getName() + "?"
						+ (max == null ? "" : " (you can sell up to *" + fmt(max) + "*)")));
			}
			case "SALE_UNITS" -> {
				Long n = wholeNumber(text);
				Long max = s.get("max") == null ? null : Long.valueOf(s.get("max"));
				if (n == null || n < 1 || n > 1_000_000) {
					return List.of(WaMessage.text("Please send the number of units, e.g. *10*."));
				}
				if (max != null && n > max) {
					return List.of(WaMessage.text("You can sell at most *" + fmt(max) + " " + s.get("unit")
							+ "*. That is all that is left in your balance. Send a smaller number."));
				}
				s.put("units", String.valueOf(n));
				s.setState("SALE_AMOUNT");
				return List.of(WaMessage.text("What is the total amount of this sale in ₹? (e.g. *25000*)"));
			}
			case "SALE_AMOUNT" -> {
				BigDecimal amount = money(text);
				if (amount == null) {
					return List.of(WaMessage.text("Please send the amount as a number, e.g. *25000* or *25,000.50*."));
				}
				s.put("amount", amount.toPlainString());
				s.setState("SALE_PAID");
				return List.of(paidPrompt());
			}
			case "SALE_PAID" -> {
				if ("paid:full".equals(reply)) {
					s.put("paid", s.get("amount"));
					return confirmSale(s);
				}
				if ("paid:part".equals(reply)) {
					s.setState("SALE_PAID_AMOUNT");
					return List.of(WaMessage.text("How much was paid now in ₹? (send *0* if nothing yet)"));
				}
				return List.of(paidPrompt());
			}
			case "SALE_PAID_AMOUNT" -> {
				BigDecimal paid = money(text);
				BigDecimal amount = new BigDecimal(s.get("amount"));
				if (paid == null || paid.compareTo(amount) > 0) {
					return List.of(WaMessage.text("Please send an amount between 0 and ₹" + amount.toPlainString() + "."));
				}
				s.put("paid", paid.toPlainString());
				return confirmSale(s);
			}
			case "SALE_CONFIRM" -> {
				if ("save:no".equals(reply)) {
					s.reset();
					return List.of(WaMessage.text("Cancelled. Nothing was saved."), menu(u));
				}
				if (!"save:yes".equals(reply)) {
					return List.of(WaMessage.buttons("Save this sale?", button("save:yes", "Save"), button("save:no", "Cancel")));
				}
				return saveSale(s, u);
			}
			default -> {
				s.reset();
				return List.of(menu(u));
			}
		}
	}

	private static WaMessage paidPrompt() {
		return WaMessage.buttons("Was it paid in full?", button("paid:full", "Fully paid"), button("paid:part", "Part on credit"));
	}

	/** Products a sale can be recorded for: a member's products with units left; any active product for the admin. */
	private List<Row> saleProducts(AppUser u) {
		List<Product> active = products.list().stream().filter(Product::isActive).toList();
		if (u.getRole() == Role.TENANT_ADMIN) {
			return active.stream().map(p -> row("p:" + p.getId(), p.getName(), p.getUnit())).toList();
		}
		Map<Long, Long> left = new java.util.HashMap<>();
		tasks.list().stream().filter(t -> u.getId().equals(t.assignedToId()))
				.forEach(t -> left.merge(t.productId(), t.ownBalance(), Long::sum));
		List<Row> rows = new ArrayList<>();
		for (Product p : active) {
			long n = left.getOrDefault(p.getId(), 0L);
			if (n > 0) {
				rows.add(row("p:" + p.getId(), p.getName(), fmt(n) + " " + p.getUnit() + " left to sell"));
			}
		}
		return rows;
	}

	private long ownBalance(AppUser u, Long productId) {
		return tasks.list().stream()
				.filter(t -> u.getId().equals(t.assignedToId()) && productId.equals(t.productId()))
				.mapToLong(TaskView::ownBalance).sum();
	}

	private List<WaMessage> confirmSale(WaSession s) {
		s.setState("SALE_CONFIRM");
		BigDecimal amount = new BigDecimal(s.get("amount"));
		BigDecimal paid = new BigDecimal(s.get("paid"));
		StringBuilder sb = new StringBuilder("Please check:\n\n👤 ").append(s.get("customerName")).append("\n💰 ")
				.append(s.get("units")).append(' ').append(s.get("unit")).append(" of ").append(s.get("productName"))
				.append(" for ₹").append(amount.toPlainString());
		if (paid.compareTo(amount) < 0) {
			sb.append("\n💳 ₹").append(paid.toPlainString()).append(" paid, ₹").append(amount.subtract(paid).toPlainString())
					.append(" on credit");
		}
		return List.of(WaMessage.buttons(sb.toString(), button("save:yes", "Save"), button("save:no", "Cancel")));
	}

	private List<WaMessage> saveSale(WaSession s, AppUser u) {
		Activity a = new Activity();
		a.setType("SALE");
		a.setCustomerId(Long.valueOf(s.get("customerId")));
		a.setActivityTime(LocalDateTime.now());
		a.setProductId(Long.valueOf(s.get("productId")));
		a.setUnitsSold(Integer.valueOf(s.get("units")));
		a.setSaleAmount(new BigDecimal(s.get("amount")));
		a.setAmountPaid(new BigDecimal(s.get("paid")));
		String problem = check(a);
		if (problem != null) {
			return List.of(WaMessage.text("⚠️ " + problem));
		}
		activities.create(a); // same rules as the website: customer access, product, balance
		String product = s.get("productName");
		Long productId = Long.valueOf(s.get("productId"));
		s.reset();
		StringBuilder sb = new StringBuilder("✅ Sale saved.");
		if (u.getRole() != Role.TENANT_ADMIN) {
			sb.append("\nYou can still sell *").append(fmt(ownBalance(u, productId))).append("* of ").append(product).append('.');
		}
		return List.of(WaMessage.buttons(sb.toString(), button("m:sale", "Record another"), button("m:menu", "Menu")));
	}

	// ======================================================================== add a customer

	/** A new customer: a name and at least one of mobile number, email or address. */
	private List<WaMessage> startCustomer(WaSession s) {
		s.reset();
		s.setState("CUST_NAME");
		return List.of(WaMessage.text("👤 *New customer*\nName?\n(Type *cancel* any time to stop.)"));
	}

	private List<WaMessage> customerStep(WaSession s, AppUser u, String text, String reply) {
		boolean skip = "skip".equals(reply) || "skip".equalsIgnoreCase(text);
		switch (s.getState()) {
			case "CUST_NAME" -> {
				if (text.isBlank() || text.length() > 250) {
					return List.of(WaMessage.text("Please send the customer's name."));
				}
				s.put("name", text);
				return askPhone(s);
			}
			case "CUST_PHONE" -> {
				if (!skip) {
					Customer probe = new Customer();
					probe.setPhone(text);
					if (!validator.validateProperty(probe, "phone").isEmpty()) {
						return List.of(WaMessage.buttons("That does not look like a mobile number. Send it again, or tap *Skip*.",
								button("skip", "Skip")));
					}
					s.put("phone", text);
				}
				s.setState("CUST_EMAIL");
				return List.of(WaMessage.buttons("Email? (or tap *Skip*)", button("skip", "Skip")));
			}
			case "CUST_EMAIL" -> {
				if (!skip) {
					Customer probe = new Customer();
					probe.setEmail(text);
					if (!validator.validateProperty(probe, "email").isEmpty()) {
						return List.of(WaMessage.buttons("That does not look like an email address. Send it again, or tap *Skip*.",
								button("skip", "Skip")));
					}
					s.put("email", text);
				}
				s.setState("CUST_ADDRESS");
				return List.of(WaMessage.buttons("Address? (or tap *Skip*)", button("skip", "Skip")));
			}
			case "CUST_ADDRESS" -> {
				if (!skip) {
					if (text.length() > 500) {
						return List.of(WaMessage.text("The address can be up to 500 characters. Please shorten it."));
					}
					s.put("address", text);
				}
				if (s.get("phone") == null && s.get("email") == null && s.get("address") == null) {
					// a name alone is not enough to reach the customer
					s.put("retry", "1");
					return askPhone(s);
				}
				s.setState("CUST_CONFIRM");
				String summary = "Please check:\n\n👤 *" + s.get("name") + "*"
						+ (s.get("phone") == null ? "" : "\n📞 " + s.get("phone"))
						+ (s.get("email") == null ? "" : "\n✉️ " + s.get("email"))
						+ (s.get("address") == null ? "" : "\n📍 " + s.get("address"));
				return List.of(WaMessage.buttons(summary, button("save:yes", "Save"), button("save:no", "Cancel")));
			}
			case "CUST_CONFIRM" -> {
				if ("save:no".equals(reply)) {
					s.reset();
					return List.of(WaMessage.text("Cancelled. Nothing was saved."), menu(u));
				}
				if (!"save:yes".equals(reply)) {
					return List.of(WaMessage.buttons("Save this customer?", button("save:yes", "Save"), button("save:no", "Cancel")));
				}
				Customer c = new Customer();
				c.setName(s.get("name"));
				c.setPhone(s.get("phone"));
				c.setEmail(s.get("email"));
				c.setAddress(s.get("address"));
				String problem = check(c);
				if (problem != null) {
					return List.of(WaMessage.text("⚠️ " + problem));
				}
				Customer saved = customers.create(c);
				s.reset(); // the buttons below start a sale or an activity with this customer already chosen
				return List.of(WaMessage.buttons("✅ Customer *" + saved.getName() + "* saved.",
						button("sale:c:" + saved.getId(), "Record sale"), button("log:c:" + saved.getId(), "Log activity"),
						button("m:menu", "Menu")));
			}
			default -> {
				s.reset();
				return List.of(menu(u));
			}
		}
	}

	private static List<WaMessage> askPhone(WaSession s) {
		s.setState("CUST_PHONE");
		String lead = s.get("retry") != null
				? "Please add at least one way to reach the customer: a mobile number, email or address.\n\n" : "";
		return List.of(WaMessage.buttons(lead + "Mobile number? (or tap *Skip*)", button("skip", "Skip")));
	}

	// ======================================================================== daily summary

	/** Today's numbers for this user, as one WhatsApp message (used by the evening job and the "summary" command). */
	public String summaryFor(AppUser u) {
		CrmUserDetails details = (CrmUserDetails) userDetails.loadUserByUsername(u.getUsername());
		return asUser(details, () -> summaryText(u));
	}

	@SuppressWarnings("unchecked")
	private String summaryText(AppUser u) {
		boolean admin = u.getRole() == Role.TENANT_ADMIN;
		var s = access.scope();
		LocalDateTime start = java.time.LocalDate.now().atStartOfDay();
		LocalDateTime end = start.plusDays(1);
		long actsMine = 0;
		long actsTeam = 0;
		for (Object[] r : activityRepo.countByOwnerBetween(s.tenantId(), s.ownerIds(), start, end)) {
			long n = ((Number) r[1]).longValue();
			if (u.getId().equals(r[0])) {
				actsMine += n;
			} else {
				actsTeam += n;
			}
		}
		Map<String, Object> me = dashboard.dashboard(null, "me");
		Map<String, Object> team = dashboard.dashboard(null, "team");
		var dayMe = ((Map<String, com.hz.crm.dashboard.DashboardController.Totals>) me.get("deals")).get("day");
		var dayAll = ((Map<String, com.hz.crm.dashboard.DashboardController.Totals>) team.get("deals")).get("day");
		long teamSize = ((Number) team.getOrDefault("teamSize", 0)).longValue();

		StringBuilder sb = new StringBuilder("📊 *Your day, ")
				.append(java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.of("en", "IN"))))
				.append("*\n");
		if (!admin) {
			sb.append("\n*You*\n  📝 Activities: ").append(fmt(actsMine))
					.append("\n  💰 Sales: ").append(dayMe.deals()).append(" deal").append(dayMe.deals() == 1 ? "" : "s")
					.append(" · ").append(fmt(dayMe.units())).append(" units · ₹").append(rupees(dayMe.amount()));
		}
		if (admin || teamSize > 0) {
			long deals = dayAll.deals() - (admin ? 0 : dayMe.deals());
			long unitsTeam = dayAll.units() - (admin ? 0 : dayMe.units());
			BigDecimal amountTeam = admin ? dayAll.amount() : dayAll.amount().subtract(dayMe.amount());
			sb.append(admin ? "\n*Company*" : "\n\n*Your team*").append("\n  📝 Activities: ")
					.append(fmt(admin ? actsMine + actsTeam : actsTeam))
					.append("\n  💰 Sales: ").append(deals).append(" deal").append(deals == 1 ? "" : "s")
					.append(" · ").append(fmt(unitsTeam)).append(" units · ₹").append(rupees(amountTeam));
		}
		Object credit = team.get("creditOutstanding");
		if (credit instanceof BigDecimal c && c.signum() > 0) {
			sb.append("\n\n💳 Still to collect (all time): ₹").append(rupees(c));
		}
		// balance and open tasks
		List<TaskView> mine = myTaskViews(u);
		long open = mine.stream().filter(t -> t.status() != Status.COMPLETED).count();
		Map<String, Long> left = new LinkedHashMap<>();
		for (TaskView t : mine) {
			left.merge(t.productName() + " (" + t.unit() + ")", admin ? t.teamBalance() : t.ownBalance(), Long::sum);
		}
		if (!left.isEmpty()) {
			sb.append(admin ? "\n\n📦 *Unsold on your tasks*" : "\n\n📦 *You can still sell*");
			left.entrySet().stream().filter(e -> e.getValue() > 0).limit(5)
					.forEach(e -> sb.append("\n  ").append(e.getKey()).append(": ").append(fmt(e.getValue())));
			if (left.values().stream().allMatch(v -> v == 0)) {
				sb.append("\n  Nothing left. 🎉");
			}
		}
		sb.append("\n\n🎯 Open tasks: ").append(open);
		return sb.toString();
	}

	private static String rupees(BigDecimal v) {
		return String.format(Locale.of("en", "IN"), "%,.0f", v == null ? BigDecimal.ZERO : v);
	}

	// ======================================================================== helpers

	/** The same field checks the website applies, as one readable message (null when fine). */
	private <T> String check(T entity) {
		var problems = validator.validate(entity);
		return problems.isEmpty() ? null
				: problems.stream().map(ConstraintViolation::getMessage).distinct().reduce((a, b) -> a + "; " + b).orElse(null);
	}

	private static Long wholeNumber(String text) {
		String t = text.replaceAll("[,\\s]", "");
		return t.matches("\\d{1,9}") ? Long.valueOf(t) : null;
	}

	private static BigDecimal money(String text) {
		String t = text.replaceAll("[₹,\\s]", "").replaceFirst("(?i)^rs\\.?", "");
		if (!t.matches("\\d{1,12}(\\.\\d{1,2})?")) {
			return null;
		}
		return new BigDecimal(t);
	}

	private static String fmt(long n) {
		return String.format(Locale.of("en", "IN"), "%,d", n);
	}

	private static String name(AppUser u) {
		return u.getFullName() != null ? u.getFullName() : u.getUsername();
	}

	private static String firstName(AppUser u) {
		return name(u).split("[ @]")[0];
	}
}
