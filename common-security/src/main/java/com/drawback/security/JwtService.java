package com.drawback.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;

/** Issues (auth-service only) and verifies (all services) signed access tokens. Thread-safe and immutable. */
public class JwtService {

    static final String CLAIM_ROLES = "roles";
    static final String CLAIM_VERIFIED = "verified";
    static final String CLAIM_USERNAME = "uname";

    private final SecretKey key;
    private final JwtProperties props;
    private final Clock clock;

    public JwtService(JwtProperties props, Clock clock) {
        if (props.secret() == null || props.secret().isBlank()) {
            throw new IllegalStateException("drawback.security.jwt.secret must be configured");
        }
        byte[] bytes = Decoders.BASE64.decode(props.secret());
        if (bytes.length < 32) {
            throw new IllegalStateException("drawback.security.jwt.secret must decode to at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.props = props;
        this.clock = clock;
    }

    public record IssuedToken(String value, String tokenId, Instant expiresAt) {}

    public IssuedToken issueAccessToken(UUID userId, String username, Set<Role> roles, boolean verified) {
        Instant now = clock.instant();
        Instant exp = now.plus(props.accessTtl());
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .id(jti)
                .issuer(props.issuer())
                .subject(userId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_ROLES, roles.stream().map(Enum::name).sorted().toList())
                .claim(CLAIM_VERIFIED, verified)
                .signWith(key) // HS256 for a 256-bit key; the algorithm is fixed by the key, never read from the token
                .compact();
        return new IssuedToken(token, jti, exp);
    }

    /** @throws JwtException (or IllegalArgumentException) when the token is malformed, tampered with, expired or foreign. */
    public AuthenticatedUser parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(props.issuer())
                .clockSkewSeconds(props.clockSkew().toSeconds())
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();

        Set<Role> roles = EnumSet.noneOf(Role.class);
        Object raw = claims.get(CLAIM_ROLES);
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                try {
                    roles.add(Role.valueOf(String.valueOf(o)));
                } catch (IllegalArgumentException ignored) {
                    // unknown role names are dropped (forward compatibility); they grant nothing
                }
            }
        }
        if (roles.isEmpty()) {
            throw new JwtException("token carries no known role");
        }
        return new AuthenticatedUser(
                UUID.fromString(claims.getSubject()),
                claims.get(CLAIM_USERNAME, String.class),
                roles,
                Boolean.TRUE.equals(claims.get(CLAIM_VERIFIED, Boolean.class)),
                claims.getId(),
                claims.getExpiration().toInstant(),
                token);
    }
}
