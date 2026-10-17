package com.drawback.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.JwtException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    static final String SECRET = "ZHJhd2JhY2stdGVzdC1zZWNyZXQtMDEyMzQ1Njc4OS1hYmNkZWY=";
    static final String OTHER_SECRET = "YS1jb21wbGV0ZWx5LWRpZmZlcmVudC1zZWNyZXQta2V5LTAxMjM0NTY=";

    private static JwtProperties props(String secret) {
        return new JwtProperties(secret, "drawback-auth", Duration.ofMinutes(15), Duration.ofDays(14), Duration.ofSeconds(30));
    }

    private final JwtService service = new JwtService(props(SECRET), Clock.systemUTC());

    @Test
    void roundTripPreservesIdentityRolesAndVerifiedFlag() {
        UUID id = UUID.randomUUID();
        var issued = service.issueAccessToken(id, "alice", EnumSet.of(Role.USER, Role.MODERATOR), true);

        AuthenticatedUser user = service.parse(issued.value());

        assertThat(user.userId()).isEqualTo(id);
        assertThat(user.username()).isEqualTo("alice");
        assertThat(user.roles()).containsExactlyInAnyOrder(Role.USER, Role.MODERATOR);
        assertThat(user.verified()).isTrue();
        assertThat(user.tokenId()).isEqualTo(issued.tokenId());
        assertThat(user.expiresAt()).isAfter(Instant.now());
    }

    @Test
    void toStringNeverLeaksTheRawToken() {
        var issued = service.issueAccessToken(UUID.randomUUID(), "alice", Set.of(Role.USER), false);
        assertThat(service.parse(issued.value()).toString()).doesNotContain(issued.value());
    }

    @Test
    void rejectsExpiredToken() {
        var past = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC);
        String token = new JwtService(props(SECRET), past).issueAccessToken(UUID.randomUUID(), "a", Set.of(Role.USER), false).value();

        assertThatThrownBy(() -> service.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() {
        String token = new JwtService(props(OTHER_SECRET), Clock.systemUTC())
                .issueAccessToken(UUID.randomUUID(), "a", Set.of(Role.USER), false).value();

        assertThatThrownBy(() -> service.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTamperedPayload() {
        String token = service.issueAccessToken(UUID.randomUUID(), "a", Set.of(Role.USER), false).value();
        String[] parts = token.split("\\.");
        String forged = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA" + "." + parts[2];

        assertThatThrownBy(() -> service.parse(forged)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsTokenFromAnotherIssuer() {
        var foreign = new JwtProperties(SECRET, "someone-else", Duration.ofMinutes(5), null, null);
        String token = new JwtService(foreign, Clock.systemUTC()).issueAccessToken(UUID.randomUUID(), "a", Set.of(Role.USER), false).value();

        assertThatThrownBy(() -> service.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> service.parse("not-a-jwt")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.parse("")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void refusesWeakOrMissingSecrets() {
        assertThatThrownBy(() -> new JwtService(props(null), Clock.systemUTC())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtService(props("c2hvcnQ="), Clock.systemUTC())).isInstanceOf(IllegalStateException.class);
    }
}
