package com.hz.crm.whatsapp;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Links WhatsApp numbers to CRM accounts with one-time codes. A code has 8 characters from a 31-letter alphabet
 * (about 850 billion combinations), lasts 10 minutes and works once; a number that sends 5 wrong codes is blocked
 * for 15 minutes, so guessing someone else's code is not practical.
 */
@Service
public class WaLinkService {

	public static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
	private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"; // no 0/O, 1/I/L
	private static final int MAX_WRONG = 5;
	private static final Duration BLOCK = Duration.ofMinutes(15);

	public record Code(String code, LocalDateTime expiresAt) {
	}

	public enum LinkResult { LINKED, ALREADY_LINKED_TO_YOU, WRONG_OR_EXPIRED, BLOCKED, NUMBER_TAKEN }

	private record Wrong(int count, Instant since) {
	}

	private final WaLinkRepository links;
	private final WaLinkCodeRepository codes;
	private final SecureRandom random = new SecureRandom();
	private final Map<String, Wrong> wrong = new ConcurrentHashMap<>();

	public WaLinkService(WaLinkRepository links, WaLinkCodeRepository codes) {
		this.links = links;
		this.codes = codes;
	}

	/** A new code for this user; any earlier code of theirs stops working. */
	@Transactional
	public Code newCode(Long userId) {
		codes.deleteByUserIdOrExpired(userId, LocalDateTime.now());
		String code;
		do {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 8; i++) {
				sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
			}
			code = sb.toString();
		} while (codes.findByCode(code).isPresent());
		WaLinkCode c = new WaLinkCode();
		c.setCode(code);
		c.setUserId(userId);
		c.setExpiresAt(LocalDateTime.now().plus(CODE_LIFETIME));
		codes.save(c);
		return new Code(code.substring(0, 4) + "-" + code.substring(4), c.getExpiresAt());
	}

	/** "k7p2 qx9m", "LINK K7P2-QX9M" ... -> "K7P2QX9M", or null if the text is not shaped like a code */
	public static String codeIn(String text) {
		if (text == null) {
			return null;
		}
		String t = text.trim().toUpperCase(Locale.ROOT);
		if (t.startsWith("LINK")) {
			t = t.substring(4);
		}
		t = t.replaceAll("[\\s-]", "");
		return t.length() == 8 && t.chars().allMatch(ch -> ALPHABET.indexOf(ch) >= 0) ? t : null;
	}

	/**
	 * True when the text is clearly meant as a code: "LINK ..." or the exact shape shown on the website
	 * ("K7P2-QX9M"). A linked number is mid-conversation, so a plain 8-letter answer must not be taken for a code.
	 */
	public static boolean isExplicitCode(String text) {
		if (text == null) {
			return false;
		}
		String t = text.trim().toUpperCase(Locale.ROOT);
		return (t.startsWith("LINK") && codeIn(t) != null) || t.matches("[A-Z0-9]{4}[- ][A-Z0-9]{4}") && codeIn(t) != null;
	}

	@Transactional
	public LinkResult link(String phone, String code) {
		Wrong w = wrong.get(phone);
		Instant now = Instant.now();
		if (w != null && w.count() >= MAX_WRONG && w.since().plus(BLOCK).isAfter(now)) {
			return LinkResult.BLOCKED;
		}
		Optional<WaLinkCode> found = codes.findByCode(code)
				.filter(c -> c.getExpiresAt().isAfter(LocalDateTime.now()));
		if (found.isEmpty()) {
			wrong.merge(phone, new Wrong(1, now), (a, b) -> a.since().plus(BLOCK).isBefore(now) ? b
					: new Wrong(a.count() + 1, a.since()));
			return LinkResult.WRONG_OR_EXPIRED;
		}
		wrong.remove(phone);
		Long userId = found.get().getUserId();
		codes.delete(found.get());
		Optional<WaLink> byPhone = links.findByPhone(phone);
		if (byPhone.isPresent()) {
			return byPhone.get().getUserId().equals(userId) ? LinkResult.ALREADY_LINKED_TO_YOU : LinkResult.NUMBER_TAKEN;
		}
		// a user has one number: linking a new one replaces the old one
		links.findByUserId(userId).ifPresent(links::delete);
		links.flush();
		WaLink l = new WaLink();
		l.setPhone(phone);
		l.setUserId(userId);
		links.save(l);
		return LinkResult.LINKED;
	}

	public Optional<WaLink> linkOf(Long userId) {
		return links.findByUserId(userId);
	}

	public Optional<WaLink> linkOfPhone(String phone) {
		return links.findByPhone(phone);
	}

	@Transactional
	public void unlink(Long userId) {
		links.findByUserId(userId).ifPresent(links::delete);
	}
}
