package com.drawback.review.service;

import com.drawback.grpc.user.UserProfile;
import com.drawback.review.client.UserDirectoryClient;
import com.drawback.review.client.UserDirectoryClient.Outcome;
import com.drawback.review.web.Dtos.AuthorProfile;
import com.drawback.review.web.Dtos.ReviewDetailResponse;
import com.drawback.review.web.Dtos.ReviewResponse;
import com.drawback.security.AuthenticatedUser;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Builds the review page with a parallel fan-out:
 *
 * <pre>
 *            ┌─> subject (Redis/DB) ─────────┐
 *   review ──┼─> other reviews (DB) ─────────┼─> combine
 *  (Redis/DB)└─> author profile (gRPC, 400ms)┘   (degrades gracefully if auth-service is slow)
 * </pre>
 *
 * Latency ≈ review + max(subject, others, author) instead of their sum. The author lookup has its own timeout and a
 * fallback so a slow dependency can never make the whole page slow or fail.
 */
@Service
public class ReviewDetailService {

    private final ReviewQueryService queries;
    private final UserDirectoryClient userDirectory;
    private final Executor io;
    private final long authorTimeoutMs;

    public ReviewDetailService(ReviewQueryService queries, UserDirectoryClient userDirectory,
                               @Qualifier("ioExecutor") Executor io,
                               @Value("${drawback.review.author-timeout-ms:400}") long authorTimeoutMs) {
        this.queries = queries;
        this.userDirectory = userDirectory;
        this.io = io;
        this.authorTimeoutMs = authorTimeoutMs;
    }

    private record Author(AuthorProfile profile, boolean degraded) {}

    public ReviewDetailResponse detail(UUID reviewId, AuthenticatedUser caller) {
        CompletableFuture<ReviewResponse> review = CompletableFuture.supplyAsync(() -> queries.getReview(reviewId), io);

        var subject = review.thenApplyAsync(r -> queries.getSubject(r.subjectId()), io);
        var others = review.thenApplyAsync(r -> queries.otherReviews(r.subjectId(), r.id()), io);
        var author = review.thenCompose(r -> CompletableFuture
                .supplyAsync(() -> lookupAuthor(r, caller), io)
                .completeOnTimeout(fallback(r), authorTimeoutMs, TimeUnit.MILLISECONDS));

        try {
            return CompletableFuture.allOf(review, subject, others, author)
                    .thenApply(v -> new ReviewDetailResponse(review.join(), subject.join(), author.join().profile(),
                            others.join(), author.join().degraded()))
                    .join();
        } catch (CompletionException e) {
            Throwable cause = e;
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof RuntimeException re) throw re; // e.g. ApiException 404 keeps its HTTP status
            throw e;
        }
    }

    private Author lookupAuthor(ReviewResponse r, AuthenticatedUser caller) {
        var lookup = userDirectory.find(r.authorId(), caller.rawToken(), authorTimeoutMs);
        if (lookup.outcome() == Outcome.FOUND) {
            UserProfile p = lookup.profile();
            return new Author(new AuthorProfile(r.authorId(), p.getUsername(), p.getDisplayName(), p.getVerified(),
                    p.getReviewCount()), false);
        }
        return fallback(r);
    }

    private static Author fallback(ReviewResponse r) {
        return new Author(new AuthorProfile(r.authorId(), r.authorName(), r.authorName(), false, 0), true);
    }
}
