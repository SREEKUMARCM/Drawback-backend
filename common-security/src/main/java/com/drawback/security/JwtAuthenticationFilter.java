package com.drawback.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Intercepts EVERY request. If it carries a valid, non-revoked bearer token the SecurityContext is populated;
 * otherwise the context stays anonymous and the authorization rules (anyRequest().authenticated()) answer 401.
 * Invalid tokens are deliberately not rejected here, so a stale token sent to a public endpoint (e.g. /login)
 * does not break that endpoint.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final TokenBlacklist blacklist;
    private final WebAuthenticationDetailsSource detailsSource = new WebAuthenticationDetailsSource();

    public JwtAuthenticationFilter(JwtService jwtService, TokenBlacklist blacklist) {
        this.jwtService = jwtService;
        this.blacklist = blacklist;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER.length()).trim();
        try {
            AuthenticatedUser user = jwtService.parse(token);
            if (blacklist.isRevoked(user.tokenId())) {
                log.debug("Rejected revoked token jti={}", user.tokenId());
                request.setAttribute(JwtAuthenticationEntryPoint.ERROR_ATTRIBUTE, "Token has been revoked");
            } else {
                var authorities = user.roles().stream().map(Role::authority).toList();
                var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
                authentication.setDetails(detailsSource.buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (JwtException | IllegalArgumentException e) {
            // never log the token itself
            log.debug("Rejected bearer token: {}", e.getClass().getSimpleName());
            request.setAttribute(JwtAuthenticationEntryPoint.ERROR_ATTRIBUTE, "Invalid or expired token");
        } catch (RuntimeException e) {
            // Blacklist store unavailable: fail CLOSED (we cannot prove the token is not revoked).
            log.error("Token blacklist unavailable", e);
            SecurityContextHolder.clearContext();
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("{\"title\":\"Service Unavailable\",\"status\":503,\"detail\":\"Authentication backend unavailable\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
