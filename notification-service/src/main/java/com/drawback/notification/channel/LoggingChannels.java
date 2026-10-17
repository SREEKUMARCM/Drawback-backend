package com.drawback.notification.channel;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Stand-ins for real providers (SES/SendGrid, FCM/APNs). Replace the bodies; the fan-out code does not change. */
public final class LoggingChannels {
    private LoggingChannels() {}

    @Component
    public static class EmailChannel implements NotificationChannel {
        private static final Logger log = LoggerFactory.getLogger(EmailChannel.class);
        @Override public String name() { return "email"; }
        @Override public void deliver(UUID recipientId, String title, String message, String link) {
            log.info("[email] to={} title={}", recipientId, title);
        }
    }

    @Component
    public static class PushChannel implements NotificationChannel {
        private static final Logger log = LoggerFactory.getLogger(PushChannel.class);
        @Override public String name() { return "push"; }
        @Override public void deliver(UUID recipientId, String title, String message, String link) {
            log.info("[push] to={} title={}", recipientId, title);
        }
    }
}
