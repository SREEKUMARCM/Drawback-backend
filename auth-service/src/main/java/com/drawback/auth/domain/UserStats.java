package com.drawback.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "user_stats")
public class UserStats {

    @Id
    @Column(name = "user_id")
    private UUID userId;
    @Column(name = "review_count")
    private long reviewCount;

    protected UserStats() {}

    public UUID getUserId() { return userId; }
    public long getReviewCount() { return reviewCount; }
}
