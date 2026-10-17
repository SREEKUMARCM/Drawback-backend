package com.drawback.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

/** Published when a subject is created or its rating aggregate changes. Carries the latest absolute aggregate. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubjectEvent(
        UUID eventId,
        Type type,
        Instant occurredAt,
        UUID subjectId,
        String slug,
        String name,
        String category,
        String description,
        UUID ownerId,
        long reviewCount,
        double averageRating) {

    public enum Type { SUBJECT_CREATED, SUBJECT_STATS_CHANGED }
}
