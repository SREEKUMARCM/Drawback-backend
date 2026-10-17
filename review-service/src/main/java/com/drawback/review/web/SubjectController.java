package com.drawback.review.web;

import com.drawback.review.service.ReviewCommandService;
import com.drawback.review.service.ReviewQueryService;
import com.drawback.review.service.SubjectService;
import com.drawback.review.web.Dtos.CreateSubjectRequest;
import com.drawback.review.web.Dtos.PageResponse;
import com.drawback.review.web.Dtos.ReviewRequest;
import com.drawback.review.web.Dtos.ReviewResponse;
import com.drawback.review.web.Dtos.SubjectResponse;
import com.drawback.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/subjects")
public class SubjectController {

    private final SubjectService subjectService;
    private final ReviewQueryService queries;
    private final ReviewCommandService commands;

    public SubjectController(SubjectService subjectService, ReviewQueryService queries, ReviewCommandService commands) {
        this.subjectService = subjectService;
        this.queries = queries;
        this.commands = commands;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SubjectResponse create(@Valid @RequestBody CreateSubjectRequest req, @AuthenticationPrincipal AuthenticatedUser user) {
        return subjectService.create(req, user);
    }

    @GetMapping("/{id}")
    public SubjectResponse get(@PathVariable UUID id) {
        return queries.getSubject(id);
    }

    @GetMapping("/{id}/reviews")
    public PageResponse<ReviewResponse> list(@PathVariable UUID id,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return queries.listForSubject(id, page, size);
    }

    @PostMapping("/{id}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewResponse review(@PathVariable UUID id, @Valid @RequestBody ReviewRequest req,
                                 @AuthenticationPrincipal AuthenticatedUser user) {
        return commands.create(id, req, user);
    }
}
