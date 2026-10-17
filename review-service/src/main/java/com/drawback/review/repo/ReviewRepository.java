package com.drawback.review.repo;

import com.drawback.review.domain.ReviewEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReviewRepository extends JpaRepository<ReviewEntity, UUID> {

    Page<ReviewEntity> findBySubjectId(UUID subjectId, Pageable pageable);

    boolean existsBySubjectIdAndAuthorId(UUID subjectId, UUID authorId);

    List<ReviewEntity> findTop5BySubjectIdAndIdNotOrderByCreatedAtDesc(UUID subjectId, UUID excludeId);

    interface Aggregate {
        long getCnt();
        long getTotal();
    }

    @Query("select count(r) as cnt, coalesce(sum(r.rating), 0) as total from ReviewEntity r where r.subjectId = :subjectId")
    Aggregate aggregateOf(UUID subjectId);
}
