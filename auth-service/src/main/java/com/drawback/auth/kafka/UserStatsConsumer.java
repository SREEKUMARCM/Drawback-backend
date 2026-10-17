package com.drawback.auth.kafka;

import com.drawback.auth.repo.UserStatsRepository;
import com.drawback.events.ReviewEvent;
import com.drawback.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps per-user review counters up to date from the review event stream.
 * Kafka is at-least-once, so each event is recorded in processed_event in the SAME transaction as the counter
 * update: a redelivery inserts nothing and is skipped (effectively-once effect).
 */
@Component
public class UserStatsConsumer {

    static final String CONSUMER = "auth-user-stats";
    private static final Logger log = LoggerFactory.getLogger(UserStatsConsumer.class);

    private final UserStatsRepository stats;

    public UserStatsConsumer(UserStatsRepository stats) {
        this.stats = stats;
    }

    @KafkaListener(topics = Topics.REVIEWS, groupId = CONSUMER,
            properties = "spring.json.value.default.type=com.drawback.events.ReviewEvent")
    @Transactional
    public void onReviewEvent(ReviewEvent event) {
        long delta = switch (event.type()) {
            case REVIEW_CREATED -> 1;
            case REVIEW_DELETED -> -1;
            case REVIEW_UPDATED -> 0;
        };
        if (delta == 0) return;
        if (stats.markProcessed(event.eventId(), CONSUMER) == 0) {
            log.debug("Duplicate event {} ignored", event.eventId());
            return;
        }
        stats.addReviews(event.authorId(), delta);
    }
}
