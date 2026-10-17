package com.drawback.review.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "review")
public class ReviewEntity {

    @Id
    private UUID id;
    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;
    @Column(name = "author_id", nullable = false)
    private UUID authorId;
    @Column(name = "author_name", nullable = false)
    private String authorName;
    @Column(nullable = false)
    private String title;
    @Column(nullable = false)
    private String body;
    @Column(nullable = false)
    private int rating;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
    /** Optimistic lock: two concurrent edits of the same review cannot silently overwrite each other. */
    @Version
    private Long version;

    protected ReviewEntity() {}

    public ReviewEntity(UUID subjectId, UUID authorId, String authorName, String title, String body, int rating) {
        this.id = UUID.randomUUID();
        this.subjectId = subjectId;
        this.authorId = authorId;
        this.authorName = authorName;
        this.title = title;
        this.body = body;
        this.rating = rating;
    }

    public void edit(String title, String body, int rating) {
        this.title = title;
        this.body = body;
        this.rating = rating;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getSubjectId() { return subjectId; }
    public UUID getAuthorId() { return authorId; }
    public String getAuthorName() { return authorName; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public int getRating() { return rating; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version == null ? 0 : version; }
}
