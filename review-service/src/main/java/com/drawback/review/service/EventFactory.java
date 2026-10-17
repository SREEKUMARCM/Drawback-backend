package com.drawback.review.service;

import com.drawback.events.ReviewEvent;
import com.drawback.events.SubjectEvent;
import com.drawback.review.domain.ReviewEntity;
import com.drawback.review.domain.SubjectEntity;
import java.time.Instant;
import java.util.UUID;

final class EventFactory {
    private EventFactory() {}

    static ReviewEvent review(ReviewEvent.Type type, ReviewEntity r, SubjectEntity s) {
        return new ReviewEvent(UUID.randomUUID(), type, Instant.now(), r.getId(), s.getId(), s.getName(), s.getCategory(),
                s.getOwnerId(), r.getAuthorId(), r.getAuthorName(), r.getTitle(), r.getBody(), r.getRating(),
                r.getCreatedAt(), r.getVersion());
    }

    static SubjectEvent subject(SubjectEvent.Type type, SubjectEntity s) {
        return new SubjectEvent(UUID.randomUUID(), type, Instant.now(), s.getId(), s.getSlug(), s.getName(),
                s.getCategory(), s.getDescription(), s.getOwnerId(), s.getReviewCount(), s.averageRating());
    }
}
