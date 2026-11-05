package com.hz.crm.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.hz.crm.user.AppUser;
import com.hz.crm.user.AppUserRepository;
import com.hz.crm.user.Role;

/** Creates the platform super admin on first start. */
@Component
public class SuperAdminSeeder implements ApplicationRunner {

	private final AppUserRepository users;
	private final PasswordEncoder encoder;
	private final String username;
	private final String password;

	public SuperAdminSeeder(AppUserRepository users, PasswordEncoder encoder,
			@Value("${crm.superadmin.username:superadmin}") String username,
			@Value("${crm.superadmin.password:superadmin123}") String password) {
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
		AppUser u = new AppUser();
		u.setUsername(username);
		u.setPasswordHash(encoder.encode(password));
		u.setFullName("Super Admin");
		u.setRole(Role.SUPER_ADMIN);
		users.save(u);
	}
}
