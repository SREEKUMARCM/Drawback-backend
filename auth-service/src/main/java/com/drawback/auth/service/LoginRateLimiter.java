package com.drawback.auth.service;

import com.drawback.security.ApiException;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Fixed-window brute-force protection: max 5 failed logins per (identifier, ip) per 15 minutes. */
@Component
public class LoginRateLimiter {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;

    public LoginRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void assertAllowed(String identifier, String ip) {
        String v = redis.opsForValue().get(key(identifier, ip));
        if (v != null && Long.parseLong(v) >= MAX_FAILURES) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts. Try again later.");
        }
    }

    public void recordFailure(String identifier, String ip) {
        String k = key(identifier, ip);
        Long n = redis.opsForValue().increment(k);
        if (n != null && n == 1L) {
            redis.expire(k, WINDOW);
        }
    }

    public void reset(String identifier, String ip) {
        redis.delete(key(identifier, ip));
    }

    private static String key(String identifier, String ip) {
        return "login:fail:" + identifier.toLowerCase() + ":" + ip;
    }
}
