package com.drawback.security.chain;

import com.drawback.security.TokenBlacklist;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication(exclude = {RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})
public class ChainTestApp {

    static final Set<String> REVOKED = ConcurrentHashMap.newKeySet();

    @Bean
    TokenBlacklist inMemoryBlacklist() {
        return new TokenBlacklist() {
            @Override public void revoke(String id, Instant exp) { REVOKED.add(id); }
            @Override public boolean isRevoked(String id) { return REVOKED.contains(id); }
        };
    }

    @RestController
    static class Probe {
        @GetMapping("/probe") String probe() { return "ok"; }
        @GetMapping("/staff") @PreAuthorize("hasRole('MODERATOR')") String staff() { return "staff"; }
        @GetMapping("/admin") @PreAuthorize("hasRole('ADMIN')") String admin() { return "admin"; }
        @GetMapping("/public/ping") String ping() { return "pong"; }
    }
}
