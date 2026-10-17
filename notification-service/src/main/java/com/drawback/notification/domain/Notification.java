package com.drawback.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification")
public class Notification {

    @Id
    private UUID id;
    @Column(name = "event_id", nullable = false)
    private UUID eventId;
    @Column(name = "recipient_id", nullable = false)
    private UUID recipientId;
    @Column(nullable = false)
    private String type;
    @Column(nullable = false)
    private String title;
    @Column(nullable = false)
    private String message;
    private String link;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() {}

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public UUID getRecipientId() { return recipientId; }
    public String getType() { return type; }
    public String getTitle() { return title; }
    public String getMessage() { return message; }
    public String getLink() { return link; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReadAt() { return readAt; }
}
