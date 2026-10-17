package com.drawback.auth.web;

import com.drawback.auth.service.AuthService;
import com.drawback.auth.service.PhoneVerificationService;
import com.drawback.auth.service.ProfileService;
import com.drawback.auth.web.AuthDtos.MeResponse;
import com.drawback.auth.web.AuthDtos.PhoneOtpRequest;
import com.drawback.auth.web.AuthDtos.PhoneOtpResponse;
import com.drawback.auth.web.AuthDtos.PhoneVerifyRequest;
import com.drawback.auth.web.AuthDtos.PhoneVerifyResponse;
import com.drawback.auth.web.AuthDtos.UpdateProfileRequest;
import com.drawback.auth.web.AuthDtos.UsernameAvailability;
import com.drawback.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's own account: profile, deletion and phone verification. */
@RestController
@RequestMapping("/api/v1/auth")
public class ProfileController {

    private static final String USERNAME = "^[A-Za-z0-9_]{3,30}$";

    private final ProfileService profiles;
    private final PhoneVerificationService phone;
    private final AuthService auth;

    public ProfileController(ProfileService profiles, PhoneVerificationService phone, AuthService auth) {
        this.profiles = profiles;
        this.phone = phone;
        this.auth = auth;
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AuthenticatedUser caller) {
        return profiles.me(caller.userId());
    }

    @PutMapping("/me")
    public MeResponse update(@AuthenticationPrincipal AuthenticatedUser caller, @Valid @RequestBody UpdateProfileRequest req) {
        return profiles.update(caller.userId(), req);
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser caller) {
        profiles.deleteAccount(caller);
    }

    /** Public (sign-up needs it before a token exists). The caller, if signed in, may keep their own name. */
    @GetMapping("/username-available")
    public UsernameAvailability usernameAvailable(@RequestParam String username, @AuthenticationPrincipal AuthenticatedUser caller) {
        if (!username.matches(USERNAME)) return new UsernameAvailability(false);
        return new UsernameAvailability(profiles.usernameAvailable(username, caller == null ? null : caller.userId()));
    }

    @PostMapping("/phone/otp")
    public PhoneOtpResponse requestOtp(@AuthenticationPrincipal AuthenticatedUser caller, @Valid @RequestBody PhoneOtpRequest req) {
        long resend = phone.requestCode(caller, req.phone());
        return new PhoneOtpResponse(300, resend);
    }

    @PostMapping("/phone/verify")
    public PhoneVerifyResponse verify(@AuthenticationPrincipal AuthenticatedUser caller, @Valid @RequestBody PhoneVerifyRequest req) {
        var user = phone.verify(caller, req.phone(), req.code());
        return new PhoneVerifyResponse(auth.reissueAccessToken(caller), MeResponse.of(user));
    }
}
