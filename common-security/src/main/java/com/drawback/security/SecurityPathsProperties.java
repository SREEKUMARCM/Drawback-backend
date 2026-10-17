package com.drawback.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * drawback.security.public-paths: the ONLY URLs reachable without a token. Defaults to health probes.
 * auth-service adds register / login / refresh (they are how a token is obtained in the first place).
 */
@ConfigurationProperties(prefix = "drawback.security")
public record SecurityPathsProperties(List<String> publicPaths) {

    public SecurityPathsProperties {
        publicPaths = publicPaths == null ? List.of("/actuator/health/**") : List.copyOf(publicPaths);
    }
}
