package com.drawback.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {

    private final JwtService jwt = new JwtService(new JwtProperties(JwtServiceTest.SECRET, null, Duration.ofMinutes(5), null, null), Clock.systemUTC());
    private final TokenBlacklist blacklist = mock(TokenBlacklist.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwt, blacklist);
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest withAuth(String value) {
        var req = new MockHttpServletRequest();
        if (value != null) req.addHeader("Authorization", value);
        return req;
    }

    @Test
    void validTokenPopulatesSecurityContextWithAuthoritiesFromRoles() throws Exception {
        String token = jwt.issueAccessToken(UUID.randomUUID(), "bob", Set.of(Role.USER, Role.ADMIN), true).value();
        var res = new MockHttpServletResponse();

        // capture the context at the moment the downstream chain runs
        var seen = new Object[1];
        org.mockito.Mockito.doAnswer(i -> { seen[0] = SecurityContextHolder.getContext().getAuthentication(); return null; })
                .when(chain).doFilter(any(), any());

        filter.doFilter(withAuth("Bearer " + token), res, chain);

        var auth = (org.springframework.security.core.Authentication) seen[0];
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
        assertThat(((AuthenticatedUser) auth.getPrincipal()).username()).isEqualTo("bob");
    }

    @Test
    void missingHeaderLeavesContextAnonymousAndContinues() throws Exception {
        var req = withAuth(null);
        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(any(), any());
    }

    @Test
    void invalidTokenStaysAnonymousAndRecordsReason() throws Exception {
        var req = withAuth("Bearer definitely.not.valid");
        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(req.getAttribute(JwtAuthenticationEntryPoint.ERROR_ATTRIBUTE)).isEqualTo("Invalid or expired token");
        verify(chain).doFilter(any(), any());
    }

    @Test
    void revokedTokenIsNotAuthenticated() throws Exception {
        var issued = jwt.issueAccessToken(UUID.randomUUID(), "bob", Set.of(Role.USER), true);
        when(blacklist.isRevoked(issued.tokenId())).thenReturn(true);
        var req = withAuth("Bearer " + issued.value());

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(req.getAttribute(JwtAuthenticationEntryPoint.ERROR_ATTRIBUTE)).isEqualTo("Token has been revoked");
    }

    @Test
    void blacklistOutageFailsClosedWith503() throws Exception {
        String token = jwt.issueAccessToken(UUID.randomUUID(), "bob", Set.of(Role.USER), true).value();
        when(blacklist.isRevoked(any())).thenThrow(new IllegalStateException("redis down"));
        var res = new MockHttpServletResponse();

        filter.doFilter(withAuth("Bearer " + token), res, chain);

        assertThat(res.getStatus()).isEqualTo(503);
        org.mockito.Mockito.verifyNoInteractions(chain);
    }

    @Test
    void nonBearerSchemeIsIgnored() throws Exception {
        filter.doFilter(withAuth("Basic dXNlcjpwYXNz"), new MockHttpServletResponse(), chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(any(), any());
    }
}
