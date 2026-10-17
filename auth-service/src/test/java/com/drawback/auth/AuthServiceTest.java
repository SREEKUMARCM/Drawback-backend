package com.drawback.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.drawback.auth.domain.UserAccount;
import com.drawback.auth.repo.UserRepository;
import com.drawback.auth.service.AuthService;
import com.drawback.auth.service.LoginRateLimiter;
import com.drawback.auth.service.RefreshTokenStore;
import com.drawback.auth.web.AuthDtos.LoginRequest;
import com.drawback.auth.web.AuthDtos.RegisterRequest;
import com.drawback.security.ApiException;
import com.drawback.security.AuthenticatedUser;
import com.drawback.security.JwtProperties;
import com.drawback.security.JwtService;
import com.drawback.security.Role;
import com.drawback.security.TokenBlacklist;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    static final String SECRET = "ZHJhd2JhY2stdGVzdC1zZWNyZXQtMDEyMzQ1Njc4OS1hYmNkZWY=";

    @Mock UserRepository users;
    @Mock TokenBlacklist blacklist;
    @Mock RefreshTokenStore refreshTokens;
    @Mock LoginRateLimiter limiter;

    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4); // cheap cost factor for tests
    JwtService jwt = new JwtService(new JwtProperties(SECRET, null, Duration.ofMinutes(15), Duration.ofDays(1), null), Clock.systemUTC());
    AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(users, encoder, jwt, blacklist, refreshTokens, limiter);
    }

    private UserAccount existingUser(String password) {
        return new UserAccount("alice", "alice@example.com", encoder.encode(password), "Alice");
    }

    // ---- register ---------------------------------------------------------------------------------------------

    @Test
    void registerStoresAHashNeverThePlainPassword() {
        when(users.usernameTaken("alice")).thenReturn(false);
        when(users.emailTaken("alice@example.com")).thenReturn(false);
        when(users.saveAndFlush(any(UserAccount.class))).thenAnswer(i -> i.getArgument(0));

        var res = service.register(new RegisterRequest("alice", "alice@example.com", "correct-horse-battery", "Alice"));

        var captor = org.mockito.ArgumentCaptor.forClass(UserAccount.class);
        verify(users).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isNotEqualTo("correct-horse-battery").startsWith("$2");
        assertThat(res.roles()).containsExactly(Role.USER);
        assertThat(res.verified()).isFalse();
    }

    @Test
    void registerRejectsDuplicateUsernameAndEmail() {
        when(users.usernameTaken("alice")).thenReturn(true);
        assertThatThrownBy(() -> service.register(new RegisterRequest("alice", "a@x.io", "correct-horse-battery", "A")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));

        when(users.usernameTaken("bob")).thenReturn(false);
        when(users.emailTaken("dup@x.io")).thenReturn(true);
        assertThatThrownBy(() -> service.register(new RegisterRequest("bob", "dup@x.io", "correct-horse-battery", "B")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ---- login ------------------------------------------------------------------------------------------------

    @Test
    void loginIssuesVerifiableTokensAndResetsFailureCounter() {
        UserAccount u = existingUser("correct-horse-battery");
        when(users.findByIdentifier("alice")).thenReturn(Optional.of(u));
        when(refreshTokens.issue(u.getId())).thenReturn("refresh-1");
        when(refreshTokens.ttl()).thenReturn(Duration.ofDays(1));

        var tokens = service.login(new LoginRequest("alice", "correct-horse-battery"), "10.0.0.1");

        AuthenticatedUser parsed = jwt.parse(tokens.accessToken());
        assertThat(parsed.userId()).isEqualTo(u.getId());
        assertThat(parsed.roles()).contains(Role.USER);
        assertThat(tokens.refreshToken()).isEqualTo("refresh-1");
        assertThat(tokens.tokenType()).isEqualTo("Bearer");
        verify(limiter).reset("alice", "10.0.0.1");
    }

    @Test
    void wrongPasswordIs401AndCountsAsFailure() {
        when(users.findByIdentifier("alice")).thenReturn(Optional.of(existingUser("correct-horse-battery")));

        assertThatThrownBy(() -> service.login(new LoginRequest("alice", "nope-nope-nope"), "10.0.0.1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(limiter).recordFailure("alice", "10.0.0.1");
        verify(refreshTokens, never()).issue(any());
    }

    @Test
    void unknownUserGetsTheSameErrorAsWrongPassword() {
        when(users.findByIdentifier("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest("ghost", "whatever-pass"), "10.0.0.1"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(e.getMessage()).isEqualTo("Invalid credentials");
                });
        verify(limiter).recordFailure("ghost", "10.0.0.1");
    }

    @Test
    void suspendedAccountCannotLogIn() {
        UserAccount u = existingUser("correct-horse-battery");
        u.setStatus(UserAccount.Status.SUSPENDED);
        when(users.findByIdentifier("alice")).thenReturn(Optional.of(u));

        assertThatThrownBy(() -> service.login(new LoginRequest("alice", "correct-horse-battery"), "ip"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void rateLimitedCallerNeverReachesPasswordCheck() {
        doThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many")).when(limiter).assertAllowed("alice", "ip");

        assertThatThrownBy(() -> service.login(new LoginRequest("alice", "x"), "ip"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verify(users, never()).findByIdentifier(anyString());
    }

    // ---- refresh / logout -------------------------------------------------------------------------------------

    @Test
    void refreshRotatesTokens() {
        UserAccount u = existingUser("pw-pw-pw-pw-pw");
        when(refreshTokens.consume("old")).thenReturn(Optional.of(u.getId()));
        when(users.findById(u.getId())).thenReturn(Optional.of(u));
        when(refreshTokens.issue(u.getId())).thenReturn("new");
        when(refreshTokens.ttl()).thenReturn(Duration.ofDays(1));

        var tokens = service.refresh("old");

        assertThat(tokens.refreshToken()).isEqualTo("new");
        assertThat(jwt.parse(tokens.accessToken()).userId()).isEqualTo(u.getId());
    }

    @Test
    void reusedRefreshTokenIs401() {
        when(refreshTokens.consume("stolen")).thenThrow(new RefreshTokenStore.ReuseDetectedException(UUID.randomUUID()));

        assertThatThrownBy(() -> service.refresh("stolen"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void unknownRefreshTokenIs401() {
        when(refreshTokens.consume("junk")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.refresh("junk")).isInstanceOf(ApiException.class);
    }

    @Test
    void logoutBlacklistsAccessTokenAndRevokesRefreshToken() {
        Instant exp = Instant.now().plusSeconds(600);
        var caller = new AuthenticatedUser(UUID.randomUUID(), "alice", Set.of(Role.USER), true, "jti-1", exp, "raw");

        service.logout(caller, "refresh-1");

        verify(blacklist).revoke("jti-1", exp);
        verify(refreshTokens).revoke("refresh-1");
    }
}
