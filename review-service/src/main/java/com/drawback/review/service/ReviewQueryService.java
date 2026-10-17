package com.drawback.review.service;

import com.drawback.review.config.ReviewConfig.CacheNames;
import com.drawback.review.repo.ReviewRepository;
import com.drawback.review.repo.SubjectRepository;
import com.drawback.review.web.Dtos.PageResponse;
import com.drawback.review.web.Dtos.ReviewResponse;
import com.drawback.review.web.Dtos.SubjectResponse;
import com.drawback.security.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read path. Hot single-entity reads are served from Redis; lists go to PostgreSQL (indexed). */
@Service
@Transactional(readOnly = true)
public class ReviewQueryService {

    private final ReviewRepository reviews;
    private final SubjectRepository subjects;

    public ReviewQueryService(ReviewRepository reviews, SubjectRepository subjects) {
        this.reviews = reviews;
        this.subjects = subjects;
    }

    @Cacheable(cacheNames = CacheNames.REVIEWS, key = "#id.toString()")
    public ReviewResponse getReview(UUID id) {
        return reviews.findById(id).map(ReviewResponse::of).orElseThrow(() -> ApiException.notFound("Review"));
    }

    @Cacheable(cacheNames = CacheNames.SUBJECTS, key = "#id.toString()")
    public SubjectResponse getSubject(UUID id) {
        return subjects.findById(id).map(SubjectResponse::of).orElseThrow(() -> ApiException.notFound("Subject"));
    }

    public PageResponse<ReviewResponse> listForSubject(UUID subjectId, int page, int size) {
        if (!subjects.existsById(subjectId)) throw ApiException.notFound("Subject");
        var p = reviews.findBySubjectId(subjectId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50), Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PageResponse<>(p.map(ReviewResponse::of).getContent(), p.getNumber(), p.getSize(), p.getTotalElements());
    }

    public List<ReviewResponse> otherReviews(UUID subjectId, UUID excludeReviewId) {
        return reviews.findTop5BySubjectIdAndIdNotOrderByCreatedAtDesc(subjectId, excludeReviewId).stream()
                .map(ReviewResponse::of).toList();
    }
}
