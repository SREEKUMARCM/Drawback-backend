package com.drawback.search.query;

import com.drawback.search.config.SolrProperties;
import com.drawback.search.query.SearchDtos.FacetValue;
import com.drawback.search.query.SearchDtos.GlobalSearchResult;
import com.drawback.search.query.SearchDtos.ReviewHit;
import com.drawback.search.query.SearchDtos.ReviewQuery;
import com.drawback.search.query.SearchDtos.ReviewSearchResult;
import com.drawback.search.query.SearchDtos.SubjectHit;
import com.drawback.search.query.SearchDtos.Suggestion;
import com.drawback.security.ApiException;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.RangeFacet;
import org.apache.solr.common.SolrDocument;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SearchService {

    static final int MAX_PAGE_SIZE = 50;
    static final int MAX_OFFSET = 10_000;          // deep paging is expensive in Solr; use cursors for exports
    static final int MAX_QUERY_LENGTH = 200;
    private static final Map<String, String> SORTS = Map.of(
            "relevance", "score desc",
            "rating", "rating desc",
            "newest", "created_at desc");

    private final SolrClient solr;
    private final SolrProperties props;
    private final QueryCache cache;
    private final Executor executor;

    public SearchService(SolrClient solr, SolrProperties props, QueryCache cache,
                         @Qualifier("searchExecutor") Executor executor) {
        this.solr = solr;
        this.props = props;
        this.cache = cache;
        this.executor = executor;
    }

    public ReviewSearchResult searchReviews(ReviewQuery rq) {
        SolrQuery q = buildReviewQuery(rq);
        String key = "reviews|" + q;   // SolrQuery.toString() renders the full parameter set
        return cache.get(key, ReviewSearchResult.class, Duration.ofSeconds(30), () -> executeReviews(q, rq));
    }

    /** Reviews + subjects are independent, so both Solr round-trips run in parallel. */
    public GlobalSearchResult searchAll(String text) {
        var reviewsF = CompletableFuture.supplyAsync(
                () -> searchReviews(new ReviewQuery(text, null, null, "relevance", 0, 10)), executor);
        var subjectsF = CompletableFuture.supplyAsync(() -> searchSubjects(text, 5), executor);
        try {
            return CompletableFuture.allOf(reviewsF, subjectsF)
                    .thenApply(v -> new GlobalSearchResult(reviewsF.join(), subjectsF.join())).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }

    public List<Suggestion> suggest(String prefix) {
        String p = clean(prefix).toLowerCase(Locale.ROOT);
        if (p.length() < 2) return List.of();
        SolrQuery q = new SolrQuery("{!prefix f=name_lc v=$p}");
        q.set("p", p);
        q.setFields("slug", "name");
        q.setSort("review_count", SolrQuery.ORDER.desc);
        q.setRows(8);
        return cache.get("suggest|" + p, Suggestions.class, Duration.ofSeconds(60), () -> {
            QueryResponse r = run(props.subjectsCollection(), q);
            return new Suggestions(r.getResults().stream()
                    .map(d -> new Suggestion((String) d.getFieldValue("slug"), (String) d.getFieldValue("name"))).toList());
        }).items();
    }

    public record Suggestions(List<Suggestion> items) {}

    // ---------------------------------------------------------------------------------------------------------------

    public SolrQuery buildReviewQuery(ReviewQuery rq) {
        String text = clean(rq.q());
        SolrQuery q = new SolrQuery(text.isEmpty() ? "*:*" : text);
        if (!text.isEmpty()) {
            q.set("defType", "edismax");
            q.set("qf", "title^4 subject_name^2 body author_name");
            q.set("pf", "title^6");          // phrase boost on title
            q.set("mm", "2<75%");            // tolerant but not sloppy
        }
        if (rq.category() != null && !rq.category().isBlank()) {
            q.addFilterQuery("{!term f=category v=$cat}");   // value passed as a parameter: no query injection
            q.set("cat", rq.category().trim().toLowerCase(Locale.ROOT));
        }
        if (rq.minRating() != null) {
            int min = Math.max(0, Math.min(100, rq.minRating()));
            q.addFilterQuery("rating:[" + min + " TO *]");
        }
        String sort = SORTS.getOrDefault(rq.sort() == null ? "relevance" : rq.sort(), SORTS.get("relevance"));
        for (String clause : sort.split(",")) {
            String[] parts = clause.trim().split(" ");
            q.addSort(parts[0], SolrQuery.ORDER.valueOf(parts[1]));
        }
        q.addSort("id", SolrQuery.ORDER.asc);   // deterministic tie-break => stable pagination

        int size = Math.min(Math.max(rq.size(), 1), MAX_PAGE_SIZE);
        int page = Math.max(rq.page(), 0);
        if ((long) page * size > MAX_OFFSET) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Page too deep; refine the query");
        }
        q.setStart(page * size);
        q.setRows(size);
        q.setFields("id", "subject_id", "subject_name", "category", "author_name", "title", "rating", "created_at", "score");

        q.setFacet(true);
        q.addFacetField("category");
        q.setFacetMinCount(1);
        q.addNumericRangeFacet("rating", 0, 101, 20);   // 0-19, 20-39, ... 100

        q.setHighlight(true);
        q.addHighlightField("title");
        q.addHighlightField("body");
        q.setHighlightSnippets(1);
        q.setHighlightFragsize(160);
        q.set("hl.encoder", "html");                    // snippets are HTML-escaped: safe to render
        q.setHighlightSimplePre("<em>");
        q.setHighlightSimplePost("</em>");
        return q;
    }

    private ReviewSearchResult executeReviews(SolrQuery q, ReviewQuery rq) {
        QueryResponse r = run(props.reviewsCollection(), q);
        var hl = r.getHighlighting();
        List<ReviewHit> hits = new ArrayList<>();
        for (SolrDocument d : r.getResults()) {
            String id = (String) d.getFieldValue("id");
            hits.add(new ReviewHit(id, str(d, "subject_id"), str(d, "subject_name"), str(d, "category"),
                    str(d, "author_name"), str(d, "title"), snippet(hl == null ? null : hl.get(id)),
                    d.getFieldValue("rating") instanceof Number n ? n.intValue() : 0,
                    d.getFieldValue("created_at") instanceof Date dt ? dt.toInstant() : null,
                    d.getFieldValue("score") instanceof Number s ? s.floatValue() : 0f));
        }
        List<FacetValue> categories = new ArrayList<>();
        FacetField ff = r.getFacetField("category");
        if (ff != null) ff.getValues().forEach(c -> categories.add(new FacetValue(c.getName(), c.getCount())));
        List<FacetValue> ratings = new ArrayList<>();
        if (r.getFacetRanges() != null) {
            for (RangeFacet<?, ?> rf : r.getFacetRanges()) {
                for (RangeFacet.Count c : rf.getCounts()) ratings.add(new FacetValue(c.getValue(), c.getCount()));
            }
        }
        int size = q.getRows() == null ? 10 : q.getRows();
        return new ReviewSearchResult(hits, r.getResults().getNumFound(), (q.getStart() == null ? 0 : q.getStart()) / size,
                size, categories, ratings, r.getQTime());
    }

    private List<SubjectHit> searchSubjects(String text, int rows) {
        String t = clean(text);
        SolrQuery q = new SolrQuery(t.isEmpty() ? "*:*" : t);
        if (!t.isEmpty()) {
            q.set("defType", "edismax");
            q.set("qf", "name^4 description");
        }
        q.setRows(rows);
        q.addSort("score", SolrQuery.ORDER.desc);
        q.addSort("review_count", SolrQuery.ORDER.desc);
        return cache.get("subjects|" + q, SubjectHits.class, Duration.ofSeconds(30), () ->
                new SubjectHits(run(props.subjectsCollection(), q).getResults().stream().map(d -> new SubjectHit(
                        str(d, "id"), str(d, "slug"), str(d, "name"), str(d, "category"),
                        d.getFieldValue("review_count") instanceof Number n ? n.longValue() : 0L,
                        d.getFieldValue("avg_rating") instanceof Number a ? a.doubleValue() : 0.0)).toList())).items();
    }

    public record SubjectHits(List<SubjectHit> items) {}

    private QueryResponse run(String collection, SolrQuery q) {
        try {
            return solr.query(collection, q);
        } catch (SolrServerException | IOException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Search is temporarily unavailable");
        }
    }

    private static String snippet(Map<String, List<String>> fields) {
        if (fields == null) return null;
        for (String f : new String[]{"body", "title"}) {
            List<String> v = fields.get(f);
            if (v != null && !v.isEmpty()) return v.get(0);
        }
        return null;
    }

    private static String str(SolrDocument d, String f) {
        Object v = d.getFieldValue(f);
        return v == null ? null : v.toString();
    }

    private static String clean(String s) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() > MAX_QUERY_LENGTH ? t.substring(0, MAX_QUERY_LENGTH) : t;
    }
}
