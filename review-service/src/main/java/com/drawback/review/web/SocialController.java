package com.drawback.review.web;

import com.drawback.review.drawback.ApiModels.DrawbackDto;
import com.drawback.review.drawback.ApiModels.FollowResult;
import com.drawback.review.drawback.ApiModels.FollowToggle;
import com.drawback.review.drawback.ApiModels.Follows;
import com.drawback.review.drawback.ApiModels.ProfileDetail;
import com.drawback.review.drawback.ApiModels.Settings;
import com.drawback.review.drawback.DrawbackStore;
import com.drawback.review.drawback.SocialService;
import com.drawback.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Per-user social data. Profiles are public-read (see application.yml); everything else is the caller's own. */
@RestController
@RequestMapping("/api/v1/social")
public class SocialController {

    private final SocialService social;
    private final DrawbackStore store;

    public SocialController(SocialService social, DrawbackStore store) {
        this.social = social;
        this.store = store;
    }

    @GetMapping("/users/{username}")
    public ProfileDetail profile(@PathVariable String username, @AuthenticationPrincipal AuthenticatedUser viewer) {
        return social.profile(username, viewer);
    }

    @PostMapping("/users/{username}/follow")
    public FollowResult follow(@PathVariable String username, @AuthenticationPrincipal AuthenticatedUser user) {
        return social.toggleFollowUser(user, username);
    }

    @GetMapping("/follows")
    public Follows follows(@AuthenticationPrincipal AuthenticatedUser user) {
        return social.follows(user.userId());
    }

    @PostMapping("/follows")
    public Follows toggle(@Valid @RequestBody FollowToggle req, @AuthenticationPrincipal AuthenticatedUser user) {
        return social.toggleFollowKey(user, req.kind(), req.key());
    }

    @GetMapping("/saved")
    public List<DrawbackDto> saved(@AuthenticationPrincipal AuthenticatedUser user) {
        return store.saved(user.userId());
    }

    @GetMapping("/settings")
    public Settings settings(@AuthenticationPrincipal AuthenticatedUser user) {
        return social.settings(user.userId());
    }

    @PutMapping("/settings")
    public Settings saveSettings(@RequestBody Settings settings, @AuthenticationPrincipal AuthenticatedUser user) {
        return social.saveSettings(user.userId(), settings);
    }

    @GetMapping("/export")
    public Map<String, Object> export(@AuthenticationPrincipal AuthenticatedUser user) {
        return social.export(user);
    }

    /** First half of account deletion (the web app then deletes the account in auth-service). */
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void purge(@AuthenticationPrincipal AuthenticatedUser user) {
        social.purge(user);
    }
}
