package com.drawback.search.index;

import com.drawback.events.ReviewEvent;
import com.drawback.events.SubjectEvent;
import java.util.Date;
import java.util.Locale;
import org.apache.solr.common.SolrInputDocument;

/** Pure mapping functions (easy to unit test). */
public final class SolrDocuments {
    private SolrDocuments() {}

    public static SolrInputDocument review(ReviewEvent e) {
        var d = new SolrInputDocument();
        d.setField("id", e.reviewId().toString());
        d.setField("subject_id", e.subjectId().toString());
        d.setField("subject_name", e.subjectName());
        d.setField("category", e.subjectCategory());
        d.setField("author_id", e.authorId().toString());
        d.setField("author_name", e.authorName());
        d.setField("title", e.title());
        d.setField("body", e.body());
        d.setField("rating", e.rating());
        d.setField("created_at", Date.from(e.reviewCreatedAt()));
        d.setField("review_version", e.version());
        return d;
    }

    public static SolrInputDocument subject(SubjectEvent e) {
        var d = new SolrInputDocument();
        d.setField("id", e.subjectId().toString());
        d.setField("slug", e.slug());
        d.setField("name", e.name());
        d.setField("name_lc", e.name().toLowerCase(Locale.ROOT));
        d.setField("category", e.category());
        d.setField("description", e.description());
        d.setField("owner_id", e.ownerId().toString());
        d.setField("review_count", e.reviewCount());
        d.setField("avg_rating", e.averageRating());
        return d;
    }
}
