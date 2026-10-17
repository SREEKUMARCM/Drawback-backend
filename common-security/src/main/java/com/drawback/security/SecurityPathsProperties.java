package com.drawback.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * drawback.security.public-paths: URLs reachable without a token for ANY HTTP method. Defaults to health probes.
 * auth-service adds register / login / refresh (they are how a token is obtained in the first place).
 *
 * drawback.security.public-read-paths: URLs reachable without a token for GET only. Used for content that guests may
 * browse (feed, drawback pages). The JWT filter still runs, so a signed-in caller is recognised on these paths;
 * every write method stays behind authentication.
 */
@ConfigurationProperties(prefix = "drawback.security")
public record SecurityPathsProperties(List<String> publicPaths, List<String> publicReadPaths) {

    public SecurityPathsProperties {
        publicPaths = publicPaths == null ? List.of("/actuator/health/**") : List.copyOf(publicPaths);
        publicReadPaths = publicReadPaths == null ? List.of() : List.copyOf(publicReadPaths);
    }
}
