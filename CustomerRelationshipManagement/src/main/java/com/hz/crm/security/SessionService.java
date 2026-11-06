package com.hz.crm.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

import com.hz.crm.user.AppUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Updates the current session after the signed-in user changed their own account. */
@Service
public class SessionService {

	private final SecurityContextRepository contextRepository;

	public SessionService(SecurityContextRepository contextRepository) {
		this.contextRepository = contextRepository;
	}

	public void refresh(AppUser u, HttpServletRequest request, HttpServletResponse response) {
		CrmUserDetails d = new CrmUserDetails(u.getId(), u.getUsername(), u.getPasswordHash(), u.isActive(),
				u.getTenantId(), u.getRole(), u.getFullName());
		d.eraseCredentials();
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(d, null, d.getAuthorities()));
		SecurityContextHolder.setContext(context);
		contextRepository.saveContext(context, request, response);
	}
}
