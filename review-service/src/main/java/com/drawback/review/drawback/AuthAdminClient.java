package com.drawback.review.drawback;

import com.drawback.security.ApiException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Moderation decisions that suspend an author must change the account in auth-service. The moderator's own JWT is
 * forwarded, so auth-service applies its normal rule (ADMIN only) and records who did it; this service holds no extra privilege.
 */
@Component
public class AuthAdminClient {

    private static final Logger log = LoggerFactory.getLogger(AuthAdminClient.class);

    private final RestClient http;

    public AuthAdminClient(RestClient.Builder builder, @Value("${drawback.review.auth-base-url:http://localhost:8081}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    public void suspend(UUID userId, String bearerToken) {
        try {
            http.put().uri("/api/v1/admin/users/{id}/suspension", userId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 403) throw ApiException.forbidden("Only an administrator can suspend an account.");
            log.warn("auth-service refused suspension: {}", e.getStatusCode());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The account service refused the request.");
        } catch (RuntimeException e) {
            log.warn("auth-service unreachable for suspension: {}", e.toString());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The account service is unavailable. Try again shortly.");
        }
    }
}
