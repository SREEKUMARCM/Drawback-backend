package com.drawback.review.web;

import com.drawback.review.service.ReviewCommandService;
import com.drawback.review.service.ReviewDetailService;
import com.drawback.review.service.ReviewQueryService;
import com.drawback.review.web.Dtos.ReviewDetailResponse;
import com.drawback.review.web.Dtos.ReviewRequest;
import com.drawback.review.web.Dtos.ReviewResponse;
import com.drawback.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewController {

    private final ReviewQueryService queries;
    private final ReviewCommandService commands;
    private final ReviewDetailService details;

    public ReviewController(ReviewQueryService queries, ReviewCommandService commands, ReviewDetailService details) {
        this.queries = queries;
        this.commands = commands;
        this.details = details;
    }

    @GetMapping("/{id}")
    public ReviewResponse get(@PathVariable UUID id) {
        return queries.getReview(id);
    }

    /** Review + subject + author + related reviews, fetched in parallel. */
    @GetMapping("/{id}/detail")
    public ReviewDetailResponse detail(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        return details.detail(id, user);
    }

    @PutMapping("/{id}")
    public ReviewResponse update(@PathVariable UUID id, @Valid @RequestBody ReviewRequest req,
                                 @AuthenticationPrincipal AuthenticatedUser user) {
        return commands.update(id, req, user);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        commands.delete(id, user);
    }
}
