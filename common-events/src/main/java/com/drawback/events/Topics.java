package com.drawback.events;

/**
 * Kafka topic names. Versioned so a breaking schema change can run side by side ("v2") during migration.
 * Partitioning keys: reviews are keyed by reviewId, subjects by subjectId, which gives per-entity ordering.
 */
public final class Topics {
    public static final String REVIEWS = "drawback.reviews.v1";
    public static final String SUBJECTS = "drawback.subjects.v1";
    /** Votes and replies on drawbacks (keyed by drawbackId). */
    public static final String DRAWBACKS = "drawback.drawbacks.v1";
    public static final String DLT_SUFFIX = ".DLT";

    private Topics() {}
}
