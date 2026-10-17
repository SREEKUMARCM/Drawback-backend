package com.drawback.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL + Redis. Run with `mvn verify` (needs Docker). */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "drawback.security.jwt.secret=ZHJhd2JhY2stdGVzdC1zZWNyZXQtMDEyMzQ1Njc4OS1hYmNkZWY=",
        "spring.kafka.listener.auto-startup=false",
        "grpc.server.port=-1",
        "grpc.server.in-process-name=auth-it"
})
@AutoConfigureMockMvc
class AuthFlowIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private JsonNode call(String url, Object body, String bearer, int expectedStatus) throws Exception {
        var req = post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (bearer != null) req.header("Authorization", "Bearer " + bearer);
        MvcResult r = mvc.perform(req).andExpect(status().is(expectedStatus)).andReturn();
        String content = r.getResponse().getContentAsString();
        return content.isBlank() ? json.createObjectNode() : json.readTree(content);
    }

    @Test
    void fullLifecycle_register_login_refreshRotation_reuseDetection_logout() throws Exception {
        call("/api/v1/auth/register", Map.of("username", "alice_it", "email", "alice@it.io",
                "password", "correct-horse-battery", "displayName", "Alice"), null, 201);
        call("/api/v1/auth/register", Map.of("username", "alice_it", "email", "other@it.io",
                "password", "correct-horse-battery", "displayName", "Dup"), null, 409);

        JsonNode login = call("/api/v1/auth/login", Map.of("identifier", "alice_it", "password", "correct-horse-battery"), null, 200);
        String access = login.get("accessToken").asText();
        String refresh1 = login.get("refreshToken").asText();

        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());           // every non-public call needs a JWT
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access)).andExpect(status().isOk());

        // RBAC: a plain USER cannot call admin endpoints
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/admin/users/" + java.util.UUID.randomUUID() + "/suspension")
                .header("Authorization", "Bearer " + access)).andExpect(status().isForbidden());

        JsonNode rotated = call("/api/v1/auth/refresh", Map.of("refreshToken", refresh1), null, 200);
        String refresh2 = rotated.get("refreshToken").asText();
        assertThat(refresh2).isNotEqualTo(refresh1);

        // replaying the already-used token is treated as theft: it fails AND kills the whole token family
        call("/api/v1/auth/refresh", Map.of("refreshToken", refresh1), null, 401);
        call("/api/v1/auth/refresh", Map.of("refreshToken", refresh2), null, 401);

        // logout revokes the access token immediately (Redis blacklist)
        JsonNode again = call("/api/v1/auth/login", Map.of("identifier", "alice@it.io", "password", "correct-horse-battery"), null, 200);
        String access2 = again.get("accessToken").asText();
        call("/api/v1/auth/logout", Map.of(), access2, 204);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access2)).andExpect(status().isUnauthorized());
    }

    @Test
    void bruteForceIsThrottledAfterFiveFailures() throws Exception {
        call("/api/v1/auth/register", Map.of("username", "bob_it", "email", "bob@it.io",
                "password", "correct-horse-battery", "displayName", "Bob"), null, 201);
        for (int i = 0; i < 5; i++) {
            call("/api/v1/auth/login", Map.of("identifier", "bob_it", "password", "wrong-wrong-wrong"), null, 401);
        }
        // even the right password is refused while the window is exhausted
        call("/api/v1/auth/login", Map.of("identifier", "bob_it", "password", "correct-horse-battery"), null, 429);
    }

    @Test
    void validationErrorsAreReportedPerField() throws Exception {
        JsonNode body = call("/api/v1/auth/register", Map.of("username", "x", "email", "not-an-email",
                "password", "short", "displayName", "X"), null, 400);
        assertThat(body.get("errors").has("username")).isTrue();
        assertThat(body.get("errors").has("email")).isTrue();
        assertThat(body.get("errors").has("password")).isTrue();
    }
}
