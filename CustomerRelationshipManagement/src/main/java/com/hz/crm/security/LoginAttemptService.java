package com.hz.crm.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Temporarily locks a login name after too many failed attempts, and an IP address after many more, to slow down
 * password guessing. State is in memory (per application instance).
 */
@Service
public class LoginAttemptService {

	private static final int IP_FACTOR = 4;
	private static final int MAX_ENTRIES = 10_000;

	private record Attempts(int failures, Instant firstFailure, Instant lockedUntil) {
	}

	private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
	private final int maxFailures;
	private final Duration lockTime;

	public LoginAttemptService(@Value("${crm.login.max-failures:5}") int maxFailures,
			@Value("${crm.login.lock-minutes:15}") long lockMinutes) {
		this.maxFailures = maxFailures;
		this.lockTime = Duration.ofMinutes(lockMinutes);
	}

	private static String userKey(String username) {
		return "u:" + username.trim().toLowerCase(Locale.ROOT);
	}

	private static String ipKey(String ip) {
		return "ip:" + ip;
	}

	/** Throws 429 if this login name or IP is currently locked. */
	public void checkAllowed(String username, String ip) {
		Instant now = Instant.now();
		for (String key : new String[] { userKey(username), ipKey(ip) }) {
			Attempts a = attempts.get(key);
			if (a != null && a.lockedUntil() != null && a.lockedUntil().isAfter(now)) {
				long minutes = Math.max(1, Duration.between(now, a.lockedUntil()).toMinutes() + 1);
				throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
						"Too many failed sign-in attempts. Try again in " + minutes + " minute(s).");
			}
		}
	}

	public void failed(String username, String ip) {
		record(userKey(username), maxFailures);
		record(ipKey(ip), maxFailures * IP_FACTOR);
	}

	public void succeeded(String username) {
		attempts.remove(userKey(username));
	}

	private void record(String key, int limit) {
		Instant now = Instant.now();
		if (attempts.size() > MAX_ENTRIES) {
			attempts.values().removeIf(a -> a.firstFailure().plus(lockTime).isBefore(now)
					&& (a.lockedUntil() == null || a.lockedUntil().isBefore(now)));
		}
		attempts.compute(key, (k, a) -> {
			// start a new window if the old one (or the old lock) has expired
			if (a == null || (a.lockedUntil() != null ? a.lockedUntil().isBefore(now)
					: a.firstFailure().plus(lockTime).isBefore(now))) {
				a = new Attempts(0, now, null);
			}
			int failures = a.failures() + 1;
			return new Attempts(failures, a.firstFailure(), failures >= limit ? now.plus(lockTime) : null);
		});
	}
}
