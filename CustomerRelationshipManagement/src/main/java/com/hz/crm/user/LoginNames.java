package com.hz.crm.user;

import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Login names have the form name@TENANTCODE. */
public final class LoginNames {

	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,30}");
	private static final Pattern CODE = Pattern.compile("[A-Za-z0-9]{1,5}");

	private LoginNames() {
	}

	public static String normalizeCode(String code) {
		if (code == null || !CODE.matcher(code.trim()).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Tenant code must be 1-5 letters or digits");
		}
		return code.trim().toUpperCase(Locale.ROOT);
	}

	public static String build(String name, String tenantCode) {
		if (name == null || !NAME.matcher(name.trim()).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"User name may only contain letters, digits, '.', '_' or '-' (no '@')");
		}
		return name.trim().toLowerCase(Locale.ROOT) + "@" + tenantCode.toUpperCase(Locale.ROOT);
	}
}
