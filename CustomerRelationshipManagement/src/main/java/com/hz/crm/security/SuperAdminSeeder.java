package com.hz.crm.security;

import java.security.SecureRandom;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.hz.crm.user.AppUser;
import com.hz.crm.user.AppUserRepository;
import com.hz.crm.user.Role;

/**
 * Creates the platform super admin on first start. The password comes from crm.superadmin.password;
 * when that is empty a random one is generated and logged once, so no default password ever ships.
 */
@Component
public class SuperAdminSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(SuperAdminSeeder.class);

	private final AppUserRepository users;
	private final PasswordEncoder encoder;
	private final String username;
	private final String password;

	public SuperAdminSeeder(AppUserRepository users, PasswordEncoder encoder,
			@Value("${crm.superadmin.username:superadmin}") String username,
			@Value("${crm.superadmin.password:}") String password) {
		this.users = users;
		this.encoder = encoder;
		this.username = username;
		this.password = password;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (users.existsByRole(Role.SUPER_ADMIN)) {
			return;
		}
		String pw = password;
		if (pw == null || pw.isBlank()) {
			byte[] bytes = new byte[12];
			new SecureRandom().nextBytes(bytes);
			pw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
			log.warn("Created super admin '{}' with generated password: {}  (change it, or set CRM_SUPERADMIN_PASSWORD)",
					username, pw);
		}
		AppUser u = new AppUser();
		u.setUsername(username);
		u.setPasswordHash(encoder.encode(pw));
		u.setFullName("Super Admin");
		u.setRole(Role.SUPER_ADMIN);
		users.save(u);
	}
}
