package com.drawback.review.service;

import com.drawback.events.ReviewEvent;
import com.drawback.events.SubjectEvent;
import com.drawback.events.Topics;
import com.drawback.review.client.UserDirectoryClient;
import com.drawback.review.client.UserDirectoryClient.Outcome;
import com.drawback.review.config.ReviewConfig.CacheNames;
import com.drawback.review.domain.ReviewEntity;
import com.drawback.review.domain.SubjectEntity;
import com.drawback.review.outbox.OutboxService;
import com.drawback.review.repo.ReviewRepository;
import com.drawback.review.repo.SubjectRepository;
import com.drawback.review.web.Dtos.ReviewRequest;
import com.drawback.review.web.Dtos.ReviewResponse;
import com.drawback.security.ApiException;
import com.drawback.security.AuthenticatedUser;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Write path. Shape of every command:
 *   1. cheap checks from the JWT (no I/O)
 *   2. remote check against auth-service over gRPC  (OUTSIDE the DB transaction: no connection held during network I/O)
 *   3. one short DB transaction: business rows + atomic aggregate delta + outbox rows
 *   4. cache eviction after commit
 */
@Service
public class ReviewCommandService {

    private final SubjectRepository subjects;
    private final ReviewRepository reviews;
    private final OutboxService outbox;
    private final TransactionTemplate tx;
    private final UserDirectoryClient userDirectory;
    private final CacheManager caches;
    private final boolean failOpen;
    private final long deadlineMs;

    public ReviewCommandService(SubjectRepository subjects, ReviewRepository reviews, OutboxService outbox,
                                TransactionTemplate tx, UserDirectoryClient userDirectory, CacheManager caches,
                                @Value("${drawback.review.user-check.fail-open:true}") boolean failOpen,
                                @Value("${drawback.review.user-check.deadline-ms:500}") long deadlineMs) {
        this.subjects = subjects;
        this.reviews = reviews;
        this.outbox = outbox;
        this.tx = tx;
        this.userDirectory = userDirectory;
        this.caches = caches;
        this.failOpen = failOpen;
        this.deadlineMs = deadlineMs;
    }

    public ReviewResponse create(UUID subjectId, ReviewRequest req, AuthenticatedUser user) {
        requireVerified(user);
        requireActiveAccount(user);
        try {
            ReviewResponse created = tx.execute(status -> {
                SubjectEntity subject = subjects.findById(subjectId).orElseThrow(() -> ApiException.notFound("Subject"));
                if (subject.getOwnerId().equals(user.userId())) {
                    throw ApiException.forbidden("You cannot review a subject you own");
                }
                if (reviews.existsBySubjectIdAndAuthorId(subjectId, user.userId())) {
                    throw ApiException.conflict("You have already reviewed this subject; edit your review instead");
                }
                ReviewEntity review = reviews.save(new ReviewEntity(subjectId, user.userId(), user.username(),
                        req.title().trim(), req.body().trim(), req.rating()));
                subjects.applyDelta(subjectId, 1, req.rating());
                publish(ReviewEvent.Type.REVIEW_CREATED, review, subjectId);
                return ReviewResponse.of(review);
            });
            evict(created.id(), subjectId);
            return created;
        } catch (DataIntegrityViolationException race) { // unique (subject, author) lost a race with a parallel request
            throw ApiException.conflict("You have already reviewed this subject");
        }
    }

    public ReviewResponse update(UUID reviewId, ReviewRequest req, AuthenticatedUser user) {
        requireVerified(user);
        ReviewResponse updated = tx.execute(status -> {
            ReviewEntity review = reviews.findById(reviewId).orElseThrow(() -> ApiException.notFound("Review"));
            if (!review.getAuthorId().equals(user.userId())) throw ApiException.forbidden("Only the author can edit a review");
            int oldRating = review.getRating();
            review.edit(req.title().trim(), req.body().trim(), req.rating());
            reviews.saveAndFlush(review); // @Version check happens here -> 409 on concurrent edit
            if (oldRating != req.rating()) subjects.applyDelta(review.getSubjectId(), 0, req.rating() - oldRating);
            publish(ReviewEvent.Type.REVIEW_UPDATED, review, review.getSubjectId());
            return ReviewResponse.of(review);
        });
        evict(reviewId, updated.subjectId());
        return updated;
    }

    public void delete(UUID reviewId, AuthenticatedUser user) {
        UUID subjectId = tx.execute(status -> {
            ReviewEntity review = reviews.findById(reviewId).orElseThrow(() -> ApiException.notFound("Review"));
            if (!review.getAuthorId().equals(user.userId()) && !user.isStaff()) {
                throw ApiException.forbidden("Only the author or a moderator can delete a review");
            }
            reviews.delete(review);
            reviews.flush();
            subjects.applyDelta(review.getSubjectId(), -1, -review.getRating());
            publish(ReviewEvent.Type.REVIEW_DELETED, review, review.getSubjectId());
            return review.getSubjectId();
        });
        evict(reviewId, subjectId);
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** Must run inside the transaction: re-reads the subject so the event carries the post-update aggregate. */
    private void publish(ReviewEvent.Type type, ReviewEntity review, UUID subjectId) {
        SubjectEntity subject = subjects.findById(subjectId).orElseThrow();
        ReviewEvent re = EventFactory.review(type, review, subject);
        outbox.enqueue(Topics.REVIEWS, review.getId().toString(), re.eventId(), re);
        SubjectEvent se = EventFactory.subject(SubjectEvent.Type.SUBJECT_STATS_CHANGED, subject);
        outbox.enqueue(Topics.SUBJECTS, subject.getId().toString(), se.eventId(), se);
    }

    private static void requireVerified(AuthenticatedUser user) {
        if (!user.verified()) throw ApiException.forbidden("Verify your account to write reviews");
    }

    /** The JWT may be up to 15 minutes old; ask auth-service whether the account is still active. */
    private void requireActiveAccount(AuthenticatedUser user) {
        var lookup = userDirectory.find(user.userId(), user.rawToken(), deadlineMs);
        if (lookup.outcome() == Outcome.UNAVAILABLE) {
            if (failOpen) return; // availability over freshness; the JWT itself is still valid
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Account service unavailable, try again shortly");
        }
        if (!lookup.isActiveUser()) throw ApiException.forbidden("Account is not active");
    }

    private void evict(UUID reviewId, UUID subjectId) {
        Cache r = caches.getCache(CacheNames.REVIEWS);
        Cache s = caches.getCache(CacheNames.SUBJECTS);
        if (r != null) r.evict(reviewId.toString());
        if (s != null) s.evict(subjectId.toString());
    }
}
