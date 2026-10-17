package com.drawback.review.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_event")
public class OutboxEvent implements org.springframework.data.domain.Persistable<UUID> {

    @Id
    private UUID id;
    @Column(nullable = false)
    private String topic;
    @Column(name = "event_key", nullable = false)
    private String eventKey;
    @Column(nullable = false)
    private String payload;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "published_at")
    private Instant publishedAt;

    @jakarta.persistence.Transient
    private boolean isNew = true;

    @jakarta.persistence.PostLoad
    @jakarta.persistence.PostPersist
    void markNotNew() { this.isNew = false; }

    @Override
    public boolean isNew() { return isNew; }

    protected OutboxEvent() {}

    public OutboxEvent(UUID id, String topic, String eventKey, String payload) {
        this.id = id;
        this.topic = topic;
        this.eventKey = eventKey;
        this.payload = payload;
    }

    @Override
    public UUID getId() { return id; }
    public String getTopic() { return topic; }
    public String getEventKey() { return eventKey; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public void markPublished() { this.publishedAt = Instant.now(); }
}
