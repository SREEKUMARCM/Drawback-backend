package com.drawback.auth.service;

import com.drawback.security.JwtProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Opaque, single-use refresh tokens kept in Redis (only a SHA-256 hash is stored, so a Redis leak is not a token leak).
 *
 * <ul>
 *   <li>rotation: every refresh consumes the old token atomically (GETDEL) and issues a new one;</li>
 *   <li>reuse detection: presenting an already-consumed token means it was stolen, so ALL sessions of that user are revoked;</li>
 *   <li>logout-all / suspension: {@link #revokeAll(UUID)}.</li>
 * </ul>
 */
@Component
public class RefreshTokenStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RefreshTokenStore(StringRedisTemplate redis, JwtProperties props) {
        this.redis = redis;
        this.ttl = props.refreshTtl();
    }

    public String issue(UUID userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = sha256(token);
        redis.opsForValue().set(activeKey(hash), userId.toString(), ttl);
        redis.opsForSet().add(userKey(userId), hash);
        redis.expire(userKey(userId), ttl);
        return token;
    }

    /** Consumes the token. Empty if unknown/expired; throws {@link ReuseDetectedException} on replay of a used token. */
    public Optional<UUID> consume(String token) {
        String hash = sha256(token);
        String userId = redis.opsForValue().getAndDelete(activeKey(hash));
        if (userId != null) {
            redis.opsForSet().remove(userKey(UUID.fromString(userId)), hash);
            redis.opsForValue().set(usedKey(hash), userId, ttl);
            return Optional.of(UUID.fromString(userId));
        }
        String replayed = redis.opsForValue().get(usedKey(hash));
        if (replayed != null) {
            UUID victim = UUID.fromString(replayed);
            revokeAll(victim);
            throw new ReuseDetectedException(victim);
        }
        return Optional.empty();
    }

    public void revoke(String token) {
        String hash = sha256(token);
        String userId = redis.opsForValue().getAndDelete(activeKey(hash));
        if (userId != null) {
            redis.opsForSet().remove(userKey(UUID.fromString(userId)), hash);
        }
    }

    public void revokeAll(UUID userId) {
        Set<String> hashes = redis.opsForSet().members(userKey(userId));
        if (hashes != null && !hashes.isEmpty()) {
            redis.delete(hashes.stream().map(RefreshTokenStore::activeKey).toList());
        }
        redis.delete(userKey(userId));
    }

    public Duration ttl() {
        return ttl;
    }

    public static class ReuseDetectedException extends RuntimeException {
        public ReuseDetectedException(UUID userId) {
            super("Refresh token reuse detected for user " + userId);
        }
    }

    private static String activeKey(String hash) { return "rt:active:" + hash; }
    private static String usedKey(String hash) { return "rt:used:" + hash; }
    private static String userKey(UUID id) { return "rt:user:" + id; }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
