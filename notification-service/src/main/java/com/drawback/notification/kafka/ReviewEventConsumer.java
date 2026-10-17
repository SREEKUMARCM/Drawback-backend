package com.drawback.notification.kafka;

import com.drawback.events.ReviewEvent;
import com.drawback.events.Topics;
import com.drawback.notification.service.NotificationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ReviewEventConsumer {

    private final NotificationService notifications;

    public ReviewEventConsumer(NotificationService notifications) {
        this.notifications = notifications;
    }

    @KafkaListener(topics = Topics.REVIEWS, groupId = "notification-service", concurrency = "3",
            properties = "spring.json.value.default.type=com.drawback.events.ReviewEvent")
    public void onReviewEvent(ReviewEvent event) {
        notifications.onReviewCreated(event);
    }
}
