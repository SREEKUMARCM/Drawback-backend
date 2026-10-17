package com.drawback.auth.repo;

import com.drawback.auth.domain.UserStats;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UserStatsRepository extends JpaRepository<UserStats, UUID> {

    /** Atomic upsert: safe under concurrent consumers, no read-modify-write race. */
    @Modifying
    @Query(value = """
            INSERT INTO user_stats (user_id, review_count) VALUES (:userId, GREATEST(:delta, 0))
            ON CONFLICT (user_id) DO UPDATE SET review_count = GREATEST(user_stats.review_count + :delta, 0)
            """, nativeQuery = true)
    int addReviews(UUID userId, long delta);

    List<UserStats> findAllByUserIdIn(Collection<UUID> ids);

    /** Returns 1 the first time an (event, consumer) pair is seen and 0 for a replay. */
    @Modifying
    @Query(value = "INSERT INTO processed_event (event_id, consumer) VALUES (:eventId, :consumer) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int markProcessed(UUID eventId, String consumer);
}
