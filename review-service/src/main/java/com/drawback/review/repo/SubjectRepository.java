package com.drawback.review.repo;

import com.drawback.review.domain.SubjectEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SubjectRepository extends JpaRepository<SubjectEntity, UUID> {

    boolean existsBySlug(String slug);

    /** Atomic aggregate delta: a single UPDATE statement, row-locked by the database for its duration only. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SubjectEntity s set s.reviewCount = s.reviewCount + :countDelta, s.ratingSum = s.ratingSum + :sumDelta where s.id = :id")
    int applyDelta(UUID id, long countDelta, long sumDelta);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SubjectEntity s where s.id = :id")
    Optional<SubjectEntity> lockById(UUID id);

    @Query("select s.id from SubjectEntity s order by s.id")
    Slice<UUID> findIds(Pageable pageable);
}
