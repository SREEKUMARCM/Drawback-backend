package com.drawback.auth.service;

import com.drawback.auth.domain.UserAccount;
import com.drawback.auth.repo.UserRepository;
import com.drawback.auth.web.AuthDtos.AccessTokenResponse;
import com.drawback.auth.web.AuthDtos.LoginRequest;
import com.drawback.auth.web.AuthDtos.RegisterRequest;
import com.drawback.auth.web.AuthDtos.TokenResponse;
import com.drawback.auth.web.AuthDtos.UserResponse;
import com.drawback.security.ApiException;
import com.drawback.security.AuthenticatedUser;
import com.drawback.security.JwtService;
import com.drawback.security.Role;
import com.drawback.security.TokenBlacklist;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final TokenBlacklist blacklist;
    private final RefreshTokenStore refreshTokens;
    private final LoginRateLimiter rateLimiter;
    /** Hash of a random password: compared against when the user does not exist so timing does not reveal accounts. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt, TokenBlacklist blacklist,
                       RefreshTokenStore refreshTokens, LoginRateLimiter rateLimiter) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.blacklist = blacklist;
        this.refreshTokens = refreshTokens;
        this.rateLimiter = rateLimiter;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public UserResponse register(RegisterRequest req) {
        if (users.usernameTaken(req.username())) throw ApiException.conflict("Username is already taken");
        if (users.emailTaken(req.email())) throw ApiException.conflict("Email is already registered");
        try {
            var user = new UserAccount(req.username(), req.email(), encoder.encode(req.password()), req.displayName());
            return UserResponse.of(users.saveAndFlush(user));
        } catch (DataIntegrityViolationException race) { // lost a race against the unique indexes
            throw ApiException.conflict("Username or email is already registered");
        }
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest req, String clientIp) {
        rateLimiter.assertAllowed(req.identifier(), clientIp);
        UserAccount user = users.findByIdentifier(req.identifier()).orElse(null);
        boolean ok = encoder.matches(req.password(), user != null ? user.getPasswordHash() : dummyHash);
        if (user == null || !ok) {
            rateLimiter.recordFailure(req.identifier(), clientIp);
            throw ApiException.unauthorized("Invalid credentials");
        }
        if (!user.isActive()) throw ApiException.forbidden("Account suspended");
        rateLimiter.reset(req.identifier(), clientIp);
        return issueTokens(user);
    }

    @Transactional(readOnly = true)
    public TokenResponse refresh(String refreshToken) {
        UUID userId;
        try {
            userId = refreshTokens.consume(refreshToken)
                    .orElseThrow(() -> ApiException.unauthorized("Invalid or expired refresh token"));
        } catch (RefreshTokenStore.ReuseDetectedException e) {
            log.warn("{} - all sessions revoked", e.getMessage());
            throw ApiException.unauthorized("Refresh token already used; please sign in again");
        }
        UserAccount user = users.findById(userId).orElseThrow(() -> ApiException.unauthorized("Unknown user"));
        if (!user.isActive()) throw ApiException.forbidden("Account suspended");
        return issueTokens(user); // roles/verified are re-read, so role changes apply at the next refresh
    }

    public void logout(AuthenticatedUser caller, String refreshToken) {
        blacklist.revoke(caller.tokenId(), caller.expiresAt());
        if (refreshToken != null && !refreshToken.isBlank()) refreshTokens.revoke(refreshToken);
    }

    public void logoutEverywhere(AuthenticatedUser caller) {
        blacklist.revoke(caller.tokenId(), caller.expiresAt());
        refreshTokens.revokeAll(caller.userId());
    }

    @Transactional(readOnly = true)
    public UserResponse me(UUID userId) {
        return users.findById(userId).map(UserResponse::of).orElseThrow(() -> ApiException.notFound("User"));
    }

    // ---- admin ----------------------------------------------------------------------------------------------------

    @Transactional
    public UserResponse setRoles(UUID userId, Set<Role> roles) {
        UserAccount user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        var granted = java.util.EnumSet.of(Role.USER);
        granted.addAll(roles);
        user.setRoles(granted);
        refreshTokens.revokeAll(userId); // force re-login so the new roles are issued
        return UserResponse.of(user);
    }

    @Transactional
    public UserResponse setVerified(UUID userId, boolean verified) {
        UserAccount user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        user.setVerified(verified);
        return UserResponse.of(user);
    }

    @Transactional
    public UserResponse suspend(UUID userId, boolean suspended) {
        UserAccount user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        user.setStatus(suspended ? UserAccount.Status.SUSPENDED : UserAccount.Status.ACTIVE);
        if (suspended) refreshTokens.revokeAll(userId); // no new access tokens; existing ones lapse within accessTtl
        return UserResponse.of(user);
    }

    /**
     * New access token carrying the account's CURRENT claims (e.g. after phone verification flipped {@code verified}).
     * The caller's refresh token stays valid; the access token that was replaced is revoked immediately.
     */
    @Transactional(readOnly = true)
    public AccessTokenResponse reissueAccessToken(AuthenticatedUser caller) {
        UserAccount user = users.findById(caller.userId()).orElseThrow(() -> ApiException.unauthorized("Unknown user"));
        if (!user.isActive()) throw ApiException.forbidden("Account suspended");
        var access = jwt.issueAccessToken(user.getId(), user.getUsername(), user.getRoles(), user.isVerified());
        blacklist.revoke(caller.tokenId(), caller.expiresAt());
        long expiresIn = java.time.Duration.between(java.time.Instant.now(), access.expiresAt()).toSeconds();
        return new AccessTokenResponse(access.value(), "Bearer", expiresIn);
    }

    private TokenResponse issueTokens(UserAccount user) {
        var access = jwt.issueAccessToken(user.getId(), user.getUsername(), user.getRoles(), user.isVerified());
        String refresh = refreshTokens.issue(user.getId());
        long expiresIn = java.time.Duration.between(java.time.Instant.now(), access.expiresAt()).toSeconds();
        return new TokenResponse(access.value(), "Bearer", expiresIn, refresh, refreshTokens.ttl().toSeconds());
    }
}
