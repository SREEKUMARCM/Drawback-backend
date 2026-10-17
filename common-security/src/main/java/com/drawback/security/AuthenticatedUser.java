package com.drawback.security;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * The principal placed in the SecurityContext after a JWT has been validated.
 * Inject it in controllers with {@code @AuthenticationPrincipal AuthenticatedUser user}.
 *
 * @param verified   account passed identity verification (e.g. phone OTP); gates write actions
 * @param rawToken   kept only so the service can forward the caller's identity on gRPC calls; never logged
 */
public record AuthenticatedUser(
        UUID userId,
        String username,
        Set<Role> roles,
        boolean verified,
        String tokenId,
        Instant expiresAt,
        String rawToken) {

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    public boolean isStaff() {
        return roles.contains(Role.MODERATOR) || roles.contains(Role.ADMIN);
    }

    @Override
    public String toString() {
        return "AuthenticatedUser[userId=" + userId + ", username=" + username + ", roles=" + roles + "]";
    }
}
