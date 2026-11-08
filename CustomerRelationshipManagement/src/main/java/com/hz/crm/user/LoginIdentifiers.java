package com.hz.crm.user;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * A user can sign in with their username (name@CODE), their email address or their mobile number. Email and mobile
 * are optional, but each one belongs to at most one account across all companies, so a sign-in always means exactly
 * one person.
 */
@Service
public class LoginIdentifiers {

	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");

	private final AppUserRepository users;

	public LoginIdentifiers(AppUserRepository users) {
		this.users = users;
	}

	/** Lower case and trimmed, or null when empty. Rejects anything that does not look like an email address. */
	public static String normalizeEmail(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String e = raw.trim().toLowerCase(Locale.ROOT);
		if (e.length() > 254 || !EMAIL.matcher(e).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid email address");
		}
		return e;
	}

	/**
	 * Digits with country code, or null when empty. Spaces, dashes, brackets and a leading + are ignored; a 10-digit
	 * number (optionally with a leading 0) is taken as an Indian number, so 98765 43210, 098765 43210 and
	 * +91 98765-43210 are all the same.
	 */
	public static String normalizeMobile(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String digits = toMobileDigits(raw);
		if (digits == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Enter a valid mobile number (10 digits, or with country code)");
		}
		return digits;
	}

	/** The normalized number, or null if the text is not a phone number at all. */
	private static String toMobileDigits(String raw) {
		String s = raw.trim();
		if (!s.matches("[+0-9 ()\\-.]{7,25}")) {
			return null;
		}
		String digits = s.replaceAll("[^0-9]", "");
		if (digits.startsWith("00")) {
			digits = digits.substring(2); // 0091... = +91...
		}
		if (digits.length() == 11 && digits.startsWith("0")) {
			digits = digits.substring(1); // 098765 43210 = 98765 43210
		}
		if (digits.length() == 10) {
			digits = "91" + digits;
		}
		return digits.length() >= 11 && digits.length() <= 15 ? digits : null;
	}

	/** The account a sign-in name refers to: username first, then email, then mobile number. */
	public Optional<AppUser> find(String identifier) {
		if (identifier == null || identifier.isBlank()) {
			return Optional.empty();
		}
		String id = identifier.trim();
		Optional<AppUser> u = users.findByUsernameIgnoreCase(id);
		if (u.isPresent()) {
			return u;
		}
		if (id.contains("@") && id.indexOf('.', id.indexOf('@')) > 0) {
			u = users.findByEmailIgnoreCase(id.toLowerCase(Locale.ROOT));
			if (u.isPresent()) {
				return u;
			}
		}
		String digits = toMobileDigits(id);
		return digits == null ? Optional.empty() : users.findByMobile(digits);
	}

	/** Throws 409 if the email or mobile already belongs to another account. */
	public void checkFree(String email, String mobile, Long ownId) {
		if (email != null && users.findByEmailIgnoreCase(email).filter(x -> !x.getId().equals(ownId)).isPresent()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "This email address is already used by another account");
		}
		if (mobile != null && users.findByMobile(mobile).filter(x -> !x.getId().equals(ownId)).isPresent()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "This mobile number is already used by another account");
		}
	}
}
