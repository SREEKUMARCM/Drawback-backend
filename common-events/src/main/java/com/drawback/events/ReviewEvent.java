package com.drawback.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by review-service (via the transactional outbox) whenever a review changes.
 * Consumers: search-service (index), notification-service (notify subject owner), auth-service (user stats).
 * Denormalised on purpose so consumers never have to call back into review-service.
 * {@code eventId} is the idempotency key for consumers with side effects.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReviewEvent(
        UUID eventId,
        Type type,
        Instant occurredAt,
        UUID reviewId,
        UUID subjectId,
        String subjectName,
        String subjectCategory,
        UUID subjectOwnerId,
        UUID authorId,
        String authorName,
        String title,
        String body,
        int rating,
        Instant reviewCreatedAt,
        long version) {

    public enum Type { REVIEW_CREATED, REVIEW_UPDATED, REVIEW_DELETED }
}
