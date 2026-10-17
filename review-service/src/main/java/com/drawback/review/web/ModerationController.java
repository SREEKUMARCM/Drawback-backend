package com.drawback.review.web;

import com.drawback.review.drawback.ApiModels.AdminStats;
import com.drawback.review.drawback.ApiModels.CaseDetail;
import com.drawback.review.drawback.ApiModels.DecisionRequest;
import com.drawback.review.drawback.ApiModels.ModerationCase;
import com.drawback.review.drawback.ModerationService;
import com.drawback.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Moderator console. MODERATOR and ADMIN (ADMIN implies MODERATOR through the role hierarchy). */
@RestController
@RequestMapping("/api/v1/admin")
public class ModerationController {

    private final ModerationService moderation;

    public ModerationController(ModerationService moderation) {
        this.moderation = moderation;
    }

    @GetMapping("/stats")
    @PreAuthorize("hasRole('MODERATOR')")
    public AdminStats stats() {
        return moderation.stats();
    }

    @GetMapping("/cases")
    @PreAuthorize("hasRole('MODERATOR')")
    public List<ModerationCase> cases(@RequestParam(required = false) String status) {
        return moderation.cases(status);
    }

    @GetMapping("/cases/{id}")
    @PreAuthorize("hasRole('MODERATOR')")
    public CaseDetail caseDetail(@PathVariable String id, @AuthenticationPrincipal AuthenticatedUser user) {
        return moderation.caseDetail(id, user);
    }

    @PostMapping("/cases/{id}/decision")
    @PreAuthorize("hasRole('MODERATOR')")
    public ModerationCase decide(@PathVariable String id, @Valid @RequestBody DecisionRequest req,
                                 @AuthenticationPrincipal AuthenticatedUser user) {
        return moderation.decide(id, req.action(), req.note(), user);
    }
}
