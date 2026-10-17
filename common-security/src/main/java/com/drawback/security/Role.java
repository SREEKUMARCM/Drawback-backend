package com.drawback.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Platform roles. ADMIN ⊃ MODERATOR ⊃ USER is expressed via the {@link org.springframework.security.access.hierarchicalroles.RoleHierarchy} bean. */
public enum Role {
    USER, MODERATOR, ADMIN;

    public GrantedAuthority authority() {
        return new SimpleGrantedAuthority("ROLE_" + name());
    }
}
