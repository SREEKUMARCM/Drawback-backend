package com.drawback.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by review-service (transactional outbox) when somebody reacts to a drawback or to a vote.
 * Consumer: notification-service. Carries everything needed to write the notification, so the consumer never calls back.
 * {@code eventId} is the idempotency key.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DrawbackEvent(
        UUID eventId,
        Type type,
        Instant occurredAt,
        UUID drawbackId,
        String headline,
        UUID recipientId,
        UUID actorId,
        String actorName,
        UUID voteId,
        Integer percent,
        String excerpt) {

    public enum Type { VOTE_CREATED, REPLY_CREATED }
}
