package com.drawback.notification.service;

import com.drawback.notification.channel.NotificationChannel;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Sends one notification over every channel in parallel; one failing channel never blocks or fails the others. */
@Component
public class ChannelDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ChannelDispatcher.class);

    private final List<NotificationChannel> channels;

    public ChannelDispatcher(List<NotificationChannel> channels) {
        this.channels = channels;
    }

    @Async("notificationExecutor")
    public CompletableFuture<Void> dispatch(UUID recipientId, String title, String message, String link) {
        var sends = channels.stream().map(ch -> CompletableFuture.runAsync(() -> {
            try {
                ch.deliver(recipientId, title, message, link);
            } catch (Exception e) {
                log.warn("Channel {} failed for {}: {}", ch.name(), recipientId, e.toString());
            }
        })).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(sends);
    }
}
