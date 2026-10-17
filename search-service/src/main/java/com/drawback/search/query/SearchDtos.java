package com.drawback.search.query;

import java.time.Instant;
import java.util.List;

public final class SearchDtos {
    private SearchDtos() {}

    public record ReviewQuery(String q, String category, Integer minRating, String sort, int page, int size) {}

    public record ReviewHit(String id, String subjectId, String subjectName, String category, String authorName,
                            String title, String snippet, int rating, Instant createdAt, float score) {}

    public record SubjectHit(String id, String slug, String name, String category, long reviewCount, double averageRating) {}

    public record FacetValue(String value, long count) {}

    public record ReviewSearchResult(List<ReviewHit> hits, long total, int page, int size,
                                     List<FacetValue> categories, List<FacetValue> ratingBuckets, long tookMs) {}

    public record GlobalSearchResult(ReviewSearchResult reviews, List<SubjectHit> subjects) {}

    public record Suggestion(String slug, String name) {}
}
