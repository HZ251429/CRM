package com.hz.crm.security;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.hz.crm.tenant.TenantRepository;
import com.hz.crm.user.AppUserRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Sessions outlive the login, so on every request re-check that the user and their tenant are still active and
 * that the password has not been changed since login. Otherwise the session is ended with a 401.
 */
public class ActiveSessionFilter extends OncePerRequestFilter {

	private final AppUserRepository users;
	private final TenantRepository tenants;

	public ActiveSessionFilter(AppUserRepository users, TenantRepository tenants) {
		this.users = users;
		this.tenants = tenants;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.getPrincipal() instanceof CrmUserDetails me && !stillValid(me)) {
			SecurityContextHolder.clearContext();
			HttpSession session = request.getSession(false);
			if (session != null) {
				session.invalidate();
			}
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.getWriter().write("{\"message\":\"Your session has ended. Please sign in again.\"}");
			return;
		}
		chain.doFilter(request, response);
	}

	private boolean stillValid(CrmUserDetails me) {
		return users.findById(me.getId())
				.filter(u -> u.isActive() && u.getPasswordHash().equals(me.getPasswordStamp()))
				.filter(u -> u.getTenantId() == null
						|| tenants.findById(u.getTenantId()).map(t -> t.isActive()).orElse(false))
				.isPresent();
	}
}
