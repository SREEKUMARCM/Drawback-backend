package com.drawback.review.repo;

import com.drawback.review.domain.OutboxEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /** SKIP LOCKED lets several replicas drain the outbox concurrently without picking the same rows. */
    @Query(value = "SELECT * FROM outbox_event WHERE published_at IS NULL ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxEvent> lockNextBatch(int limit);

    @Modifying
    @Query("delete from OutboxEvent e where e.publishedAt is not null and e.publishedAt < :before")
    int deletePublishedBefore(Instant before);
}
