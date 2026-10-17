package com.drawback.review;

import static com.drawback.review.TestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.drawback.grpc.user.UserProfile;
import com.drawback.review.client.UserDirectoryClient;
import com.drawback.review.client.UserDirectoryClient.Lookup;
import com.drawback.review.client.UserDirectoryClient.Outcome;
import com.drawback.review.service.ReviewDetailService;
import com.drawback.review.service.ReviewQueryService;
import com.drawback.review.web.Dtos.ReviewResponse;
import com.drawback.review.web.Dtos.SubjectResponse;
import com.drawback.security.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ReviewDetailServiceTest {

    final ExecutorService pool = Executors.newFixedThreadPool(8);
    final ReviewQueryService queries = mock(ReviewQueryService.class);
    final UserDirectoryClient users = mock(UserDirectoryClient.class);
    final UUID reviewId = UUID.randomUUID();
    final UUID subjectId = UUID.randomUUID();
    final UUID authorId = UUID.randomUUID();
    final ReviewResponse review = new ReviewResponse(reviewId, subjectId, authorId, "author", "Title", "Body", 70, Instant.now(), Instant.now());
    final SubjectResponse subject = new SubjectResponse(subjectId, "slug", "Subject", "cat", "d", UUID.randomUUID(), 3, 66.7);

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    private ReviewDetailService service(long authorTimeoutMs) {
        return new ReviewDetailService(queries, users, pool, authorTimeoutMs);
    }

    private void stubHappyPath() {
        when(queries.getReview(reviewId)).thenReturn(review);
        when(queries.getSubject(subjectId)).thenReturn(subject);
        when(queries.otherReviews(subjectId, reviewId)).thenReturn(List.of());
    }

    @Test
    void combinesReviewSubjectAndAuthor() {
        stubHappyPath();
        var profile = UserProfile.newBuilder().setId(authorId.toString()).setUsername("alice").setDisplayName("Alice")
                .setVerified(true).setReviewCount(12).build();
        when(users.find(any(), any(), anyLong())).thenReturn(new Lookup(Outcome.FOUND, profile));

        var detail = service(400).detail(reviewId, user(UUID.randomUUID(), true));

        assertThat(detail.review()).isEqualTo(review);
        assertThat(detail.subject()).isEqualTo(subject);
        assertThat(detail.author().username()).isEqualTo("alice");
        assertThat(detail.author().reviewCount()).isEqualTo(12);
        assertThat(detail.authorDegraded()).isFalse();
    }

    @Test
    void independentLookupsReallyRunConcurrently() throws Exception {
        // Both queries block until BOTH are in flight. Sequential execution would time out at the latch.
        var bothRunning = new CountDownLatch(2);
        when(queries.getReview(reviewId)).thenReturn(review);
        when(queries.getSubject(subjectId)).thenAnswer(i -> {
            bothRunning.countDown();
            assertThat(bothRunning.await(3, TimeUnit.SECONDS)).as("other lookup ran in parallel").isTrue();
            return subject;
        });
        when(queries.otherReviews(subjectId, reviewId)).thenAnswer(i -> {
            bothRunning.countDown();
            assertThat(bothRunning.await(3, TimeUnit.SECONDS)).as("subject lookup ran in parallel").isTrue();
            return List.of();
        });
        when(users.find(any(), any(), anyLong())).thenReturn(new Lookup(Outcome.UNAVAILABLE, null));

        var detail = service(400).detail(reviewId, user(UUID.randomUUID(), true));

        assertThat(detail.subject()).isEqualTo(subject);
    }

    @Test
    void slowAuthServiceDegradesTheAuthorInsteadOfSlowingOrFailingThePage() {
        stubHappyPath();
        when(users.find(any(), any(), anyLong())).thenAnswer(i -> {
            Thread.sleep(2_000);
            return new Lookup(Outcome.UNAVAILABLE, null);
        });

        long t0 = System.nanoTime();
        var detail = service(100).detail(reviewId, user(UUID.randomUUID(), true));
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertThat(detail.authorDegraded()).isTrue();
        assertThat(detail.author().username()).isEqualTo("author");   // fallback from the review itself
        assertThat(ms).isLessThan(1_500);                              // did not wait for the 2s call
    }

    @Test
    void unavailableAuthServiceDegrades() {
        stubHappyPath();
        when(users.find(any(), any(), anyLong())).thenReturn(new Lookup(Outcome.UNAVAILABLE, null));

        assertThat(service(400).detail(reviewId, user(UUID.randomUUID(), true)).authorDegraded()).isTrue();
    }

    @Test
    void missingReviewKeepsItsHttp404() {
        when(queries.getReview(reviewId)).thenThrow(ApiException.notFound("Review"));

        assertThatThrownBy(() -> service(400).detail(reviewId, user(UUID.randomUUID(), true)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(404));
    }
}
