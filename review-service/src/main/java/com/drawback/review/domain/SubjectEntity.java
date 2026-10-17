package com.drawback.review.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Something that can be reviewed (product, company, place, service...).
 * The aggregate (review_count, rating_sum) is maintained with atomic SQL increments, never read-modify-write,
 * so concurrent reviews cannot lose updates. Average = rating_sum / review_count.
 */
@Entity
@Table(name = "subject")
public class SubjectEntity {

    @Id
    private UUID id;
    @Column(nullable = false, unique = true)
    private String slug;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false)
    private String category;
    private String description;
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;
    @Column(name = "review_count", nullable = false)
    private long reviewCount;
    @Column(name = "rating_sum", nullable = false)
    private long ratingSum;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @jakarta.persistence.Version
    private Long version;

    protected SubjectEntity() {}

    public SubjectEntity(String slug, String name, String category, String description, UUID ownerId) {
        this.id = UUID.randomUUID();
        this.slug = slug;
        this.name = name;
        this.category = category;
        this.description = description;
        this.ownerId = ownerId;
    }

    public double averageRating() {
        return reviewCount == 0 ? 0.0 : Math.round(ratingSum * 10.0 / reviewCount) / 10.0;
    }

    public UUID getId() { return id; }
    public String getSlug() { return slug; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public String getDescription() { return description; }
    public UUID getOwnerId() { return ownerId; }
    public long getReviewCount() { return reviewCount; }
    public long getRatingSum() { return ratingSum; }
    public Instant getCreatedAt() { return createdAt; }

    /** Used only by the reconciliation job, under a pessimistic lock. */
    public void overwriteAggregate(long count, long sum) {
        this.reviewCount = count;
        this.ratingSum = sum;
    }
}
