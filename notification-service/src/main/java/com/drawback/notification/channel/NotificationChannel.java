package com.drawback.notification.channel;

import java.util.UUID;

/** A delivery mechanism (email, mobile push, websocket...). Implementations must be thread-safe and idempotent-ish. */
public interface NotificationChannel {

    String name();

    void deliver(UUID recipientId, String title, String message, String link) throws Exception;
}
