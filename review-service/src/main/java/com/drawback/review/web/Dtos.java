package com.drawback.review.web;

import com.drawback.review.domain.ReviewEntity;
import com.drawback.review.domain.SubjectEntity;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Dtos {
    private Dtos() {}

    public record CreateSubjectRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 60) String category,
            @Size(max = 2000) String description) {}

    /** rating is a 0-100 score; the subject's average is the arithmetic mean of its reviews' ratings. */
    public record ReviewRequest(
            @NotBlank @Size(min = 3, max = 160) String title,
            @NotBlank @Size(min = 20, max = 5000) String body,
            @Min(0) @Max(100) int rating) {}

    public record SubjectResponse(UUID id, String slug, String name, String category, String description,
                                  UUID ownerId, long reviewCount, double averageRating) {
        public static SubjectResponse of(SubjectEntity s) {
            return new SubjectResponse(s.getId(), s.getSlug(), s.getName(), s.getCategory(), s.getDescription(),
                    s.getOwnerId(), s.getReviewCount(), s.averageRating());
        }
    }

    public record ReviewResponse(UUID id, UUID subjectId, UUID authorId, String authorName, String title, String body,
                                 int rating, Instant createdAt, Instant updatedAt) {
        public static ReviewResponse of(ReviewEntity r) {
            return new ReviewResponse(r.getId(), r.getSubjectId(), r.getAuthorId(), r.getAuthorName(), r.getTitle(),
                    r.getBody(), r.getRating(), r.getCreatedAt(), r.getUpdatedAt());
        }
    }

    public record AuthorProfile(UUID id, String username, String displayName, boolean verified, long reviewCount) {}

    /** {@code authorDegraded=true} means auth-service was slow/unavailable and {@code author} is a minimal fallback. */
    public record ReviewDetailResponse(ReviewResponse review, SubjectResponse subject, AuthorProfile author,
                                       List<ReviewResponse> otherReviews, boolean authorDegraded) {}

    public record PageResponse<T>(List<T> items, int page, int size, long total) {}

    public record ReconcileReport(int subjectsChecked, int subjectsCorrected, long millis) {}
}
