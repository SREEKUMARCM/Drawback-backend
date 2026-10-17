package com.drawback.notification.kafka;

import com.drawback.events.DrawbackEvent;
import com.drawback.events.Topics;
import com.drawback.notification.service.NotificationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Votes and replies on drawbacks -> notifications for the person who wrote the drawback / the comment. */
@Component
public class DrawbackEventConsumer {

    private final NotificationService notifications;

    public DrawbackEventConsumer(NotificationService notifications) {
        this.notifications = notifications;
    }

    @KafkaListener(topics = Topics.DRAWBACKS, groupId = "notification-service", concurrency = "3",
            properties = "spring.json.value.default.type=com.drawback.events.DrawbackEvent")
    public void onDrawbackEvent(DrawbackEvent event) {
        notifications.onDrawbackEvent(event);
    }
}
