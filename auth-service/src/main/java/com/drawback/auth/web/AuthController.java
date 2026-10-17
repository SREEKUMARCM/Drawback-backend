package com.drawback.auth.web;

import com.drawback.auth.service.AuthService;
import com.drawback.auth.web.AuthDtos.LoginRequest;
import com.drawback.auth.web.AuthDtos.LogoutRequest;
import com.drawback.auth.web.AuthDtos.RefreshRequest;
import com.drawback.auth.web.AuthDtos.RegisterRequest;
import com.drawback.auth.web.AuthDtos.TokenResponse;
import com.drawback.auth.web.AuthDtos.UserResponse;
import com.drawback.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * register / login / refresh are the only unauthenticated endpoints in the whole platform (they are how a token is
 * obtained). Everything else, including logout and /me, requires a valid JWT.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest req) {
        return auth.register(req);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        return auth.login(req, clientIp(http));
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return auth.refresh(req.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal AuthenticatedUser caller, @RequestBody(required = false) LogoutRequest req) {
        auth.logout(caller, req == null ? null : req.refreshToken());
    }

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll(@AuthenticationPrincipal AuthenticatedUser caller) {
        auth.logoutEverywhere(caller);
    }

    /** Behind a trusted gateway/ingress, configure server.forward-headers-strategy=native so this is the real client IP. */
    private static String clientIp(HttpServletRequest http) {
        return http.getRemoteAddr();
    }
}
