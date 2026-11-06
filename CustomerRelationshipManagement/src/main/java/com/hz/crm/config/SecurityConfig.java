package com.hz.crm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

import com.hz.crm.security.ActiveSessionFilter;
import com.hz.crm.security.CrmUserDetailsService;
import com.hz.crm.security.SpaCsrfTokenRequestHandler;
import com.hz.crm.tenant.TenantRepository;
import com.hz.crm.user.AppUserRepository;

/**
 * Session-cookie authentication for the SPA:
 * <ul>
 * <li>POST /api/auth/login creates a server-side session (HttpOnly JSESSIONID cookie); the browser never stores the
 * password.</li>
 * <li>CSRF protection uses the XSRF-TOKEN cookie, which the frontend echoes back in the X-XSRF-TOKEN header.</li>
 * <li>Unauthenticated API calls get a plain 401 (no browser login popup).</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain filterChain(HttpSecurity http, CookieCsrfTokenRepository csrfRepository,
			SecurityContextRepository contextRepository, AppUserRepository users, TenantRepository tenants)
			throws Exception {
		http.csrf(c -> c.csrfTokenRepository(csrfRepository).csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
			.cors(Customizer.withDefaults())
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.securityContext(c -> c.securityContextRepository(contextRepository))
			.exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
			.logout(l -> l.logoutUrl("/api/auth/logout")
				.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
			.addFilterBefore(new ActiveSessionFilter(users, tenants), AuthorizationFilter.class)
			.authorizeHttpRequests(a -> a
				.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
				.requestMatchers("/api/auth/login", "/api/auth/csrf", "/error").permitAll()
				.requestMatchers("/api/admin/**").hasRole("SUPER_ADMIN")
				.anyRequest().authenticated());
		return http.build();
	}

	@Bean
	CookieCsrfTokenRepository csrfTokenRepository() {
		CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
		repo.setCookieCustomizer(c -> c.sameSite("Lax"));
		return repo;
	}

	@Bean
	SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	@Bean
	AuthenticationManager authenticationManager(CrmUserDetailsService userDetailsService, PasswordEncoder encoder) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
		provider.setPasswordEncoder(encoder);
		return new ProviderManager(provider);
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}
}
