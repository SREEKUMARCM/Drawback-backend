package com.drawback.security.chain;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.drawback.security.JwtProperties;
import com.drawback.security.JwtService;
import com.drawback.security.Role;
import com.drawback.security.TokenBlacklist;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** Integration test of the real filter chain: every URL except the configured public ones demands a valid JWT. */
@SpringBootTest(classes = ChainTestApp.class, properties = {
        "drawback.security.jwt.secret=ZHJhd2JhY2stdGVzdC1zZWNyZXQtMDEyMzQ1Njc4OS1hYmNkZWY=",
        "drawback.security.public-paths=/public/**"
})
@AutoConfigureMockMvc
class SecurityChainTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired TokenBlacklist blacklist;

    private String token(Role... roles) {
        return jwt.issueAccessToken(UUID.randomUUID(), "u", EnumSet.copyOf(Set.of(roles)), true).value();
    }

    @Test
    void publicPathNeedsNoToken() throws Exception {
        mvc.perform(get("/public/ping")).andExpect(status().isOk());
    }

    @Test
    void protectedPathWithoutTokenIs401WithProblemBody() throws Exception {
        mvc.perform(get("/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"drawback\""))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void unknownPathsAreProtectedToo() throws Exception {
        mvc.perform(get("/does/not/exist")).andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenIsAccepted() throws Exception {
        mvc.perform(get("/probe").header("Authorization", "Bearer " + token(Role.USER))).andExpect(status().isOk());
    }

    @Test
    void userCannotReachModeratorEndpoint() throws Exception {
        mvc.perform(get("/staff").header("Authorization", "Bearer " + token(Role.USER))).andExpect(status().isForbidden());
    }

    @Test
    void roleHierarchyLetsAdminDoEverything() throws Exception {
        String admin = token(Role.ADMIN);
        mvc.perform(get("/staff").header("Authorization", "Bearer " + admin)).andExpect(status().isOk());
        mvc.perform(get("/admin").header("Authorization", "Bearer " + admin)).andExpect(status().isOk());
    }

    @Test
    void moderatorCannotReachAdminEndpoint() throws Exception {
        mvc.perform(get("/admin").header("Authorization", "Bearer " + token(Role.MODERATOR))).andExpect(status().isForbidden());
    }

    @Test
    void expiredTokenIs401() throws Exception {
        var past = Clock.fixed(Instant.now().minus(Duration.ofHours(3)), ZoneOffset.UTC);
        String expired = new JwtService(new JwtProperties("ZHJhd2JhY2stdGVzdC1zZWNyZXQtMDEyMzQ1Njc4OS1hYmNkZWY=", null, null, null, null), past)
                .issueAccessToken(UUID.randomUUID(), "u", Set.of(Role.USER), true).value();

        mvc.perform(get("/probe").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid or expired token"));
    }

    @Test
    void tokenSignedWithWrongKeyIs401() throws Exception {
        String forged = new JwtService(new JwtProperties("YS1jb21wbGV0ZWx5LWRpZmZlcmVudC1zZWNyZXQta2V5LTAxMjM0NTY=", null, null, null, null), Clock.systemUTC())
                .issueAccessToken(UUID.randomUUID(), "u", Set.of(Role.ADMIN), true).value();

        mvc.perform(get("/admin").header("Authorization", "Bearer " + forged)).andExpect(status().isUnauthorized());
    }

    @Test
    void revokedTokenIs401() throws Exception {
        var issued = jwt.issueAccessToken(UUID.randomUUID(), "u", Set.of(Role.USER), true);
        mvc.perform(get("/probe").header("Authorization", "Bearer " + issued.value())).andExpect(status().isOk());

        blacklist.revoke(issued.tokenId(), issued.expiresAt()); // what POST /logout does

        mvc.perform(get("/probe").header("Authorization", "Bearer " + issued.value()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Token has been revoked"));
    }

    @Test
    void staleTokenOnPublicEndpointDoesNotBreakIt() throws Exception {
        mvc.perform(get("/public/ping").header("Authorization", "Bearer garbage")).andExpect(status().isOk());
    }
}
