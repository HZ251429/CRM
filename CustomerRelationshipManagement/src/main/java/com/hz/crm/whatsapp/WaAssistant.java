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

	public WaAssistant(WaLinkService linkService, WaSessionRepository sessions, AppUserRepository users,
			CrmUserDetailsService userDetails, SalesTaskController tasks, ProductController products,
			CustomerController customers, ActivityController activities, Validator validator) {
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
				case "tasks", "my tasks" -> {
					s.reset();
					return myTasks(u);
				}
				case "log", "activity", "sale", "log activity" -> {
					return startLog(s);
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
					return startLog(s);
				}
				case "m:balance" -> {
					s.reset();
					return balance(u);
				}
				case "m:tasks" -> {
					s.reset();
					return myTasks(u);
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
				}
			}
		}
		String state = s.getState();
		if (state.startsWith("LOG_")) {
			return logStep(s, u, text, reply);
		}
		if (state.startsWith("CUST_")) {
			return customerStep(s, u, text, reply);
		}
		return List.of(WaMessage.text("Sorry, I did not understand that."), menu(u));
	}

	private WaMessage menu(AppUser u) {
		List<Row> rows = new ArrayList<>();
		rows.add(row("m:log", "📝 Log activity / sale", "A meeting, call or visit, with or without a sale"));
		rows.add(row("m:balance", "📦 My balance", u.getRole() == Role.TENANT_ADMIN ? "Units still unsold, per product"
				: "Units you can still sell, per product"));
		rows.add(row("m:tasks", "🎯 My tasks", "Open tasks and their progress"));
		rows.add(row("m:customer", "👤 Add customer", "Save a new customer"));
		rows.add(row("m:help", "❓ Help", "Commands you can type"));
		return WaMessage.list("Hi " + firstName(u) + "! What would you like to do?", "Menu", rows);
	}

	private static WaMessage help() {
		return WaMessage.text("""
				You can tap *Menu* or type:
				• *log* – log a meeting, call, visit or sale
				• *balance* – units you can still sell
				• *tasks* – your open tasks
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
		return List.of(WaMessage.buttons(sb.toString().trim(), button("m:log", "Log a sale"), button("m:tasks", "My tasks"),
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
		return List.of(WaMessage.buttons(sb.toString(), button("m:log", "Log a sale"), button("m:balance", "My balance"),
				button("m:menu", "Menu")));
	}

	// ======================================================================== log an activity / sale

	private List<WaMessage> startLog(WaSession s) {
		s.reset();
		s.setState("LOG_CUSTOMER");
		return List.of(WaMessage.text("📝 *Log activity*\nWhich customer? Type part of their name, email or company.\n"
				+ "(Type *cancel* any time to stop.)"));
	}

	private List<WaMessage> logStep(WaSession s, AppUser u, String text, String reply) {
		switch (s.getState()) {
			case "LOG_CUSTOMER" -> {
				if (reply != null && reply.startsWith("c:")) {
					Customer c = customers.get(Long.valueOf(reply.substring(2))).getBody();
					if (c == null) {
						return List.of(WaMessage.text("That customer is not available. Type a name to search again."));
					}
					s.put("customerId", String.valueOf(c.getId()));
					s.put("customerName", c.getFirstName() + " " + c.getLastName());
					s.setState("LOG_TYPE");
					return List.of(WaMessage.list("Customer: *" + s.get("customerName") + "*\nWhat kind of activity?", "Choose type",
							TYPES.entrySet().stream().map(e -> row("t:" + e.getKey(), e.getValue(), null)).toList()));
				}
				if (text.isBlank()) {
					return List.of(WaMessage.text("Type part of the customer's name, email or company."));
				}
				List<Customer> found = customers.list(text, null, 0, 10).content();
				if (found.isEmpty()) {
					return List.of(WaMessage.buttons("No customers match \"" + text + "\". Try another name, or add the customer first.",
							button("m:customer", "Add customer"), button("m:menu", "Menu")));
				}
				return List.of(WaMessage.list("Customers matching \"" + text + "\" (or type another name):", "Choose customer",
						found.stream().map(c -> row("c:" + c.getId(), c.getFirstName() + " " + c.getLastName(),
								c.getCompany() == null || c.getCompany().isBlank() ? c.getEmail() : c.getCompany())).toList()));
			}
			case "LOG_TYPE" -> {
				if (reply == null || !reply.startsWith("t:") || !TYPES.containsKey(reply.substring(2))) {
					return List.of(WaMessage.text("Please choose the type from the list."));
				}
				s.put("type", reply.substring(2));
				s.setState("LOG_TITLE");
				return List.of(WaMessage.text("What was it about? Send a short title (e.g. _Price discussion for March order_)."));
			}
			case "LOG_TITLE" -> {
				if (text.isBlank() || text.length() > 200) {
					return List.of(WaMessage.text("Please send a title of up to 200 characters."));
				}
				s.put("subject", text);
				s.setState("LOG_NOTES");
				return List.of(WaMessage.buttons("Any notes? Send them, or tap *Skip*.", button("skip", "Skip")));
			}
			case "LOG_NOTES" -> {
				if (!"skip".equals(reply) && !"skip".equalsIgnoreCase(text)) {
					if (text.length() > 2000) {
						return List.of(WaMessage.text("Notes can be up to 2000 characters. Please shorten them."));
					}
					s.put("notes", text);
				}
				s.setState("LOG_SALE");
				return List.of(WaMessage.buttons("Did this include a sale?", button("sale:yes", "Yes, a sale"),
						button("sale:no", "No sale")));
			}
			case "LOG_SALE" -> {
				if ("sale:no".equals(reply)) {
					return confirmLog(s);
				}
				if (!"sale:yes".equals(reply)) {
					return List.of(WaMessage.buttons("Did this include a sale?", button("sale:yes", "Yes, a sale"),
							button("sale:no", "No sale")));
				}
				List<Row> rows = saleProducts(u);
				if (rows.isEmpty()) {
					return List.of(WaMessage.buttons(u.getRole() == Role.TENANT_ADMIN ? "There are no active products."
							: "You have no units left to sell on any task, so this can only be saved without a sale.",
							button("sale:no", "Save without sale"), button("cancel", "Cancel")));
				}
				s.setState("LOG_PRODUCT");
				return List.of(WaMessage.list("Which product was sold?", "Choose product", rows));
			}
			case "LOG_PRODUCT" -> {
				if ("cancel".equals(reply)) {
					s.reset();
					return List.of(WaMessage.text("Cancelled. Nothing was saved."), menu(u));
				}
				if ("sale:no".equals(reply)) {
					return confirmLog(s);
				}
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
				s.setState("LOG_UNITS");
				return List.of(WaMessage.text("How many " + p.getUnit() + " of " + p.getName() + "?"
						+ (max == null ? "" : " (you can sell up to *" + fmt(max) + "*)")));
			}
			case "LOG_UNITS" -> {
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
				s.setState("LOG_AMOUNT");
				return List.of(WaMessage.text("What is the total amount of this deal in ₹? (e.g. *25000*)"));
			}
			case "LOG_AMOUNT" -> {
				BigDecimal amount = money(text);
				if (amount == null) {
					return List.of(WaMessage.text("Please send the amount as a number, e.g. *25000* or *25,000.50*."));
				}
				s.put("amount", amount.toPlainString());
				s.setState("LOG_PAID");
				return List.of(WaMessage.buttons("Was it paid in full?", button("paid:full", "Fully paid"),
						button("paid:part", "Part on credit")));
			}
			case "LOG_PAID" -> {
				if ("paid:full".equals(reply)) {
					s.put("paid", s.get("amount"));
					return confirmLog(s);
				}
				if ("paid:part".equals(reply)) {
					s.setState("LOG_PAID_AMOUNT");
					return List.of(WaMessage.text("How much was paid now in ₹? (send *0* if nothing yet)"));
				}
				return List.of(WaMessage.buttons("Was it paid in full?", button("paid:full", "Fully paid"),
						button("paid:part", "Part on credit")));
			}
			case "LOG_PAID_AMOUNT" -> {
				BigDecimal paid = money(text);
				BigDecimal amount = new BigDecimal(s.get("amount"));
				if (paid == null || paid.compareTo(amount) > 0) {
					return List.of(WaMessage.text("Please send an amount between 0 and ₹" + amount.toPlainString() + "."));
				}
				s.put("paid", paid.toPlainString());
				return confirmLog(s);
			}
			case "LOG_CONFIRM" -> {
				if ("save:no".equals(reply)) {
					s.reset();
					return List.of(WaMessage.text("Cancelled. Nothing was saved."), menu(u));
				}
				if (!"save:yes".equals(reply)) {
					return List.of(WaMessage.buttons("Save this entry?", button("save:yes", "Save"), button("save:no", "Cancel")));
				}
				return saveLog(s, u);
			}
			default -> {
				s.reset();
				return List.of(menu(u));
			}
		}
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

	private List<WaMessage> confirmLog(WaSession s) {
		s.setState("LOG_CONFIRM");
		StringBuilder sb = new StringBuilder("Please check:\n\n");
		sb.append("👤 ").append(s.get("customerName")).append('\n');
		sb.append(TYPES.get(s.get("type"))).append(": *").append(s.get("subject")).append("*\n");
		if (s.get("notes") != null) {
			sb.append("🗒 ").append(s.get("notes")).append('\n');
		}
		if (s.get("productId") != null) {
			BigDecimal amount = new BigDecimal(s.get("amount"));
			BigDecimal paid = new BigDecimal(s.get("paid"));
			sb.append("💰 Sale: ").append(s.get("units")).append(' ').append(s.get("unit")).append(" of ")
					.append(s.get("productName")).append(" for ₹").append(amount.toPlainString());
			if (paid.compareTo(amount) < 0) {
				sb.append(" (₹").append(paid.toPlainString()).append(" paid, ₹")
						.append(amount.subtract(paid).toPlainString()).append(" on credit)");
			}
			sb.append('\n');
		}
		return List.of(WaMessage.buttons(sb.toString().trim(), button("save:yes", "Save"), button("save:no", "Cancel")));
	}

	private List<WaMessage> saveLog(WaSession s, AppUser u) {
		Activity a = new Activity();
		a.setCustomerId(Long.valueOf(s.get("customerId")));
		a.setType(s.get("type"));
		a.setSubject(s.get("subject"));
		a.setNotes(s.get("notes"));
		a.setActivityTime(LocalDateTime.now());
		boolean sale = s.get("productId") != null;
		if (sale) {
			a.setProductId(Long.valueOf(s.get("productId")));
			a.setUnitsSold(Integer.valueOf(s.get("units")));
			a.setSaleAmount(new BigDecimal(s.get("amount")));
			a.setAmountPaid(new BigDecimal(s.get("paid")));
		}
		String problem = check(a);
		if (problem != null) {
			return List.of(WaMessage.text("⚠️ " + problem));
		}
		activities.create(a); // same rules as the website: customer access, product, balance
		String product = s.get("productName");
		Long productId = sale ? Long.valueOf(s.get("productId")) : null;
		s.reset();
		StringBuilder sb = new StringBuilder("✅ Saved.");
		if (sale && u.getRole() != Role.TENANT_ADMIN) {
			sb.append("\nYou can still sell *").append(fmt(ownBalance(u, productId))).append("* of ").append(product).append('.');
		}
		return List.of(backToMenu(sb.toString()));
	}

	// ======================================================================== add a customer

	private List<WaMessage> startCustomer(WaSession s) {
		s.reset();
		s.setState("CUST_FIRST");
		return List.of(WaMessage.text("👤 *New customer*\nFirst name?\n(Type *cancel* any time to stop.)"));
	}

	private List<WaMessage> customerStep(WaSession s, AppUser u, String text, String reply) {
		boolean skip = "skip".equals(reply) || "skip".equalsIgnoreCase(text);
		switch (s.getState()) {
			case "CUST_FIRST" -> {
				if (text.isBlank() || text.length() > 100) {
					return List.of(WaMessage.text("Please send the first name."));
				}
				s.put("first", text);
				s.setState("CUST_LAST");
				return List.of(WaMessage.text("Last name?"));
			}
			case "CUST_LAST" -> {
				if (text.isBlank() || text.length() > 100) {
					return List.of(WaMessage.text("Please send the last name."));
				}
				s.put("last", text);
				s.setState("CUST_EMAIL");
				return List.of(WaMessage.text("Email address?"));
			}
			case "CUST_EMAIL" -> {
				Customer probe = new Customer();
				probe.setEmail(text);
				if (text.isBlank() || !validator.validateProperty(probe, "email").isEmpty()) {
					return List.of(WaMessage.text("That does not look like an email address. Please send it again."));
				}
				s.put("email", text);
				s.setState("CUST_PHONE");
				return List.of(WaMessage.buttons("Phone number? (or tap *Skip*)", button("skip", "Skip")));
			}
			case "CUST_PHONE" -> {
				if (!skip) {
					s.put("phone", text);
				}
				s.setState("CUST_COMPANY");
				return List.of(WaMessage.buttons("Company? (or tap *Skip*)", button("skip", "Skip")));
			}
			case "CUST_COMPANY" -> {
				if (!skip) {
					s.put("company", text);
				}
				s.setState("CUST_CONFIRM");
				String summary = "Please check:\n\n👤 *" + s.get("first") + " " + s.get("last") + "*\n✉️ " + s.get("email")
						+ (s.get("phone") == null ? "" : "\n📞 " + s.get("phone"))
						+ (s.get("company") == null ? "" : "\n🏢 " + s.get("company"));
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
				c.setFirstName(s.get("first"));
				c.setLastName(s.get("last"));
				c.setEmail(s.get("email"));
				c.setPhone(s.get("phone"));
				c.setCompany(s.get("company"));
				String problem = check(c);
				if (problem != null) {
					return List.of(WaMessage.text("⚠️ " + problem));
				}
				Customer saved = customers.create(c);
				s.reset();
				// go straight on to logging an activity for the new customer if they want
				s.setState("LOG_CUSTOMER");
				return List.of(WaMessage.buttons("✅ Customer *" + saved.getFirstName() + " " + saved.getLastName() + "* saved.",
						button("c:" + saved.getId(), "Log activity now"), button("m:menu", "Menu")));
			}
			default -> {
				s.reset();
				return List.of(menu(u));
			}
		}
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
