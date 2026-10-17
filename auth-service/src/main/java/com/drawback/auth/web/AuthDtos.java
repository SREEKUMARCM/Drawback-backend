package com.drawback.auth.web;

import com.drawback.auth.domain.UserAccount;
import com.drawback.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
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

    // ---- profile & phone verification (used by the Drawback web app) ----------------------------------------------

    /** Everything the signed-in user may see about their own account. The phone number is only ever returned masked. */
    public record MeResponse(UUID id, String username, String email, String displayName, boolean verified,
                             Set<Role> roles, String status, String bio, String location, List<String> interests,
                             String avatarUrl, boolean onboarded, String phoneMasked, Instant createdAt) {
        public static MeResponse of(UserAccount u) {
            return new MeResponse(u.getId(), u.getUsername(), u.getEmail(), u.getDisplayName(), u.isVerified(),
                    u.getRoles(), u.getStatus().name(), u.getBio(), u.getLocation(), u.getInterests(),
                    u.getAvatarUrl(), u.isOnboarded(), u.getPhoneMasked(), u.getCreatedAt());
        }
    }

    /** Partial update: null fields are left unchanged. */
    public record UpdateProfileRequest(
            @Pattern(regexp = "^[A-Za-z0-9_]{3,30}$", message = "3-30 letters, digits or underscores") String username,
            @Size(min = 2, max = 80) String displayName,
            @Size(max = 160) String bio,
            @Size(max = 60) String location,
            @Size(max = 20) List<@Pattern(regexp = "^[a-z0-9-]{1,40}$") String> interests,
            @Size(max = 500) String avatarUrl,
            Boolean onboarded) {}

    public record UsernameAvailability(boolean available) {}

    public record PhoneOtpRequest(@NotBlank @Pattern(regexp = "^\\+?[0-9 ()-]{8,20}$", message = "Enter a valid phone number") String phone) {}

    public record PhoneOtpResponse(long expiresIn, long resendIn) {}

    public record PhoneVerifyRequest(
            @NotBlank @Pattern(regexp = "^\\+?[0-9 ()-]{8,20}$", message = "Enter a valid phone number") String phone,
            @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "Enter the 6-digit code") String code) {}

    /** Verification changes the {@code verified} claim, so a fresh access token is returned together with the profile. */
    public record PhoneVerifyResponse(AccessTokenResponse token, MeResponse user) {}

    public record AccessTokenResponse(String accessToken, String tokenType, long expiresIn) {}
}
