package com.drawback.auth.web;

import com.drawback.auth.domain.UserAccount;
import com.drawback.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_]{3,30}$", message = "3-30 letters, digits or underscores") String username,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 10, max = 72, message = "10-72 characters") String password,
            @NotBlank @Size(max = 80) String displayName) {}

    public record LoginRequest(@NotBlank String identifier, @NotBlank String password) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    public record LogoutRequest(String refreshToken) {}

    public record RolesRequest(@NotEmpty Set<Role> roles) {}

    public record TokenResponse(String accessToken, String tokenType, long expiresIn, String refreshToken, long refreshExpiresIn) {}

    public record UserResponse(UUID id, String username, String email, String displayName,
                               boolean verified, Set<Role> roles, String status) {
        public static UserResponse of(UserAccount u) {
            return new UserResponse(u.getId(), u.getUsername(), u.getEmail(), u.getDisplayName(),
                    u.isVerified(), u.getRoles(), u.getStatus().name());
        }
    }
}
