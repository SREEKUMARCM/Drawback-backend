package com.drawback.review;

import com.drawback.security.AuthenticatedUser;
import com.drawback.security.Role;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;

final class TestFixtures {
    private TestFixtures() {}

    static AuthenticatedUser user(UUID id, boolean verified, Role... roles) {
        var set = EnumSet.noneOf(Role.class);
        set.addAll(java.util.List.of(roles.length == 0 ? new Role[]{Role.USER} : roles));
        return new AuthenticatedUser(id, "user-" + id.toString().substring(0, 4), set, verified, "jti-" + id,
                Instant.now().plusSeconds(600), "raw-jwt-" + id);
    }
}
