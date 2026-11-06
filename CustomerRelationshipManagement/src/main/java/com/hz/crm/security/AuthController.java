package com.hz.crm.security;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.hz.crm.user.MeController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Session login for the SPA. Logout is POST /api/auth/logout (handled by Spring Security). */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	public record LoginRequest(@NotBlank @Size(max = 100) String username, @NotBlank @Size(max = 200) String password) {
	}

	private final AuthenticationManager authenticationManager;
	private final SecurityContextRepository contextRepository;
	private final SessionAuthenticationStrategy sessionStrategy;
	private final LoginAttemptService attempts;
	private final MeController me;

	public AuthController(AuthenticationManager authenticationManager, SecurityContextRepository contextRepository,
			CookieCsrfTokenRepository csrfRepository, LoginAttemptService attempts, MeController me) {
		this.authenticationManager = authenticationManager;
		this.contextRepository = contextRepository;
		CsrfAuthenticationStrategy csrfStrategy = new CsrfAuthenticationStrategy(csrfRepository);
		csrfStrategy.setRequestHandler(new SpaCsrfTokenRequestHandler());
		// new session id (prevents session fixation) and a fresh CSRF token after login
		this.sessionStrategy = new CompositeSessionAuthenticationStrategy(
				List.of(new ChangeSessionIdAuthenticationStrategy(), csrfStrategy));
		this.attempts = attempts;
		this.me = me;
	}

	/**
	 * Return login errors directly. Going through the /error page would lose the message, because an unauthenticated
	 * error dispatch is answered with a bare 401.
	 */
	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<Map<String, Object>> loginError(ResponseStatusException e) {
		return ResponseEntity.status(e.getStatusCode())
				.body(Map.of("status", e.getStatusCode().value(), "message", String.valueOf(e.getReason())));
	}

	/** Issues the XSRF-TOKEN cookie; call once before the first POST (e.g. login). */
	@GetMapping("/csrf")
	public Map<String, String> csrf(CsrfToken token) {
		return Map.of("headerName", token.getHeaderName());
	}

	@PostMapping("/login")
	public Map<String, Object> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request,
			HttpServletResponse response) {
		String username = req.username().trim();
		String ip = request.getRemoteAddr();
		attempts.checkAllowed(username, ip);
		Authentication auth;
		try {
			auth = authenticationManager
					.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, req.password()));
		} catch (DisabledException e) {
			attempts.failed(username, ip);
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "This account or its company is disabled");
		} catch (AuthenticationException e) {
			attempts.failed(username, ip);
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
		}
		attempts.succeeded(username);

		request.getSession(true);
		sessionStrategy.onAuthentication(auth, request, response);
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(auth);
		SecurityContextHolder.setContext(context);
		contextRepository.saveContext(context, request, response);
		return me.me();
	}
}
