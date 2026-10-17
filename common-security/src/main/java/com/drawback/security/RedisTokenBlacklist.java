package com.drawback.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Redis-backed blacklist: one key per revoked jti with a TTL equal to the token's remaining lifetime. */
public class RedisTokenBlacklist implements TokenBlacklist {

    private static final String PREFIX = "jwt:bl:";

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisTokenBlacklist(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public void revoke(String tokenId, Instant tokenExpiresAt) {
        Duration ttl = Duration.between(clock.instant(), tokenExpiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return; // already expired, nothing to remember
        }
        redis.opsForValue().set(PREFIX + tokenId, "1", ttl);
    }

    @Override
    public boolean isRevoked(String tokenId) {
        return Boolean.TRUE.equals(redis.hasKey(PREFIX + tokenId));
    }
}
