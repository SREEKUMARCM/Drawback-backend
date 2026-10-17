package com.drawback.auth.service;

import com.drawback.auth.domain.UserAccount;
import com.drawback.auth.repo.UserRepository;
import com.drawback.auth.web.AuthDtos.MeResponse;
import com.drawback.auth.web.AuthDtos.UpdateProfileRequest;
import com.drawback.security.ApiException;
import com.drawback.security.AuthenticatedUser;
import com.drawback.security.TokenBlacklist;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final RefreshTokenStore refreshTokens;
    private final TokenBlacklist blacklist;

    public ProfileService(UserRepository users, PasswordEncoder encoder, RefreshTokenStore refreshTokens, TokenBlacklist blacklist) {
        this.users = users;
        this.encoder = encoder;
        this.refreshTokens = refreshTokens;
        this.blacklist = blacklist;
    }

    @Transactional(readOnly = true)
    public MeResponse me(UUID userId) {
        return users.findById(userId).map(MeResponse::of).orElseThrow(() -> ApiException.notFound("User"));
    }

    @Transactional
    public MeResponse update(UUID userId, UpdateProfileRequest req) {
        UserAccount user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        if (req.username() != null && !req.username().equalsIgnoreCase(user.getUsername())) {
            if (users.usernameTaken(req.username())) throw ApiException.conflict("That username is already taken");
            user.setUsername(req.username());
        }
        if (req.displayName() != null && !req.displayName().isBlank()) user.setDisplayName(req.displayName().trim());
        if (req.bio() != null) user.setBio(req.bio().trim());
        if (req.location() != null) user.setLocation(req.location().isBlank() ? null : req.location().trim());
        if (req.interests() != null) user.setInterests(req.interests().stream().distinct().toList());
        if (req.avatarUrl() != null) user.setAvatarUrl(req.avatarUrl().isBlank() ? null : req.avatarUrl().trim());
        if (Boolean.TRUE.equals(req.onboarded())) user.setOnboarded(true);
        try {
            return MeResponse.of(users.saveAndFlush(user));
        } catch (DataIntegrityViolationException race) {
            throw ApiException.conflict("That username is already taken");
        }
    }

    /** Free for the caller's own current name, so the sign-up form does not flag a user's existing username as taken. */
    @Transactional(readOnly = true)
    public boolean usernameAvailable(String username, UUID callerId) {
        return users.findByUsername(username).map(u -> u.getId().equals(callerId)).orElse(true);
    }

    /**
     * Removes everything personal and locks the account. The row stays so content in other services that references the
     * id does not dangle; it now shows as "Deleted user". All sessions are ended.
     */
    @Transactional
    public void deleteAccount(AuthenticatedUser caller) {
        UserAccount user = users.findById(caller.userId()).orElseThrow(() -> ApiException.notFound("User"));
        user.anonymise(encoder.encode(UUID.randomUUID().toString()));
        users.saveAndFlush(user);
        refreshTokens.revokeAll(user.getId());
        blacklist.revoke(caller.tokenId(), caller.expiresAt());
    }
}
