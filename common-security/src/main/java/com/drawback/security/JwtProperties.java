package com.drawback.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * drawback.security.jwt.*
 *
 * @param secret    Base64 of at least 32 random bytes (HS256). Inject from a secret manager, never from git.
 *                  For a larger fleet switch to RS256/EdDSA so only auth-service holds the private key.
 */
@ConfigurationProperties(prefix = "drawback.security.jwt")
public record JwtProperties(String secret, String issuer, Duration accessTtl, Duration refreshTtl, Duration clockSkew) {

    public JwtProperties {
        issuer = issuer == null || issuer.isBlank() ? "drawback-auth" : issuer;
        accessTtl = accessTtl == null ? Duration.ofMinutes(15) : accessTtl;
        refreshTtl = refreshTtl == null ? Duration.ofDays(14) : refreshTtl;
        clockSkew = clockSkew == null ? Duration.ofSeconds(30) : clockSkew;
    }
}
