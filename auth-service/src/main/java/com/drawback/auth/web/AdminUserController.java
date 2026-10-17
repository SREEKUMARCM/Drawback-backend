package com.drawback.auth.web;

import com.drawback.auth.service.AuthService;
import com.drawback.auth.web.AuthDtos.RolesRequest;
import com.drawback.auth.web.AuthDtos.UserResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** RBAC administration. Method security is evaluated against the role hierarchy (ADMIN ⊃ MODERATOR ⊃ USER). */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AuthService auth;

    public AdminUserController(AuthService auth) {
        this.auth = auth;
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse setRoles(@PathVariable UUID id, @Valid @RequestBody RolesRequest req) {
        return auth.setRoles(id, req.roles());
    }

    @PutMapping("/{id}/suspension")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse suspend(@PathVariable UUID id) {
        return auth.suspend(id, true);
    }

    @PutMapping("/{id}/reinstatement")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse reinstate(@PathVariable UUID id) {
        return auth.suspend(id, false);
    }

    @PutMapping("/{id}/verification")
    @PreAuthorize("hasRole('MODERATOR')")
    public UserResponse verify(@PathVariable UUID id) {
        return auth.setVerified(id, true);
    }
}
