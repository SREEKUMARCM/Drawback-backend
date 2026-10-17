package com.drawback.search.index;

import com.drawback.events.ReviewEvent;
import com.drawback.events.SubjectEvent;
import com.drawback.events.Topics;
import com.drawback.search.config.SolrProperties;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrInputDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Event-driven index synchronisation.
 *
 * Idempotent by construction: documents are keyed by entity id, so replaying or re-delivering an event just overwrites
 * the same document. Within a batch only the LAST event per entity matters (events for one key are ordered because the
 * producer keys by id), which collapses bursts of edits into one write. A failure throws, the shared error handler
 * retries with backoff and finally parks the batch on the .DLT topic.
 * To rebuild the index from scratch: reset the consumer group offsets to earliest (topics are the source of truth).
 */
@Component
public class SearchIndexer {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexer.class);

    private final SolrClient solr;
    private final SolrProperties props;

    public SearchIndexer(SolrClient solr, SolrProperties props) {
        this.solr = solr;
        this.props = props;
    }

    @KafkaListener(topics = Topics.REVIEWS, groupId = "search-indexer-reviews", containerFactory = "batchFactory",
            properties = "spring.json.value.default.type=com.drawback.events.ReviewEvent")
    public void onReviews(List<ReviewEvent> events) throws SolrServerException, IOException {
        Map<String, ReviewEvent> latest = new LinkedHashMap<>();
        for (ReviewEvent e : events) latest.put(e.reviewId().toString(), e); // later events win

        List<SolrInputDocument> upserts = new ArrayList<>();
        List<String> deletes = new ArrayList<>();
        latest.forEach((id, e) -> {
            if (e.type() == ReviewEvent.Type.REVIEW_DELETED) deletes.add(id);
            else upserts.add(SolrDocuments.review(e));
        });

        String c = props.reviewsCollection();
        if (!upserts.isEmpty()) solr.add(c, upserts, props.commitWithinMs());
        if (!deletes.isEmpty()) solr.deleteById(c, deletes, props.commitWithinMs());
        log.debug("Indexed {} review upserts, {} deletes", upserts.size(), deletes.size());
    }

    @KafkaListener(topics = Topics.SUBJECTS, groupId = "search-indexer-subjects", containerFactory = "batchFactory",
            properties = "spring.json.value.default.type=com.drawback.events.SubjectEvent")
    public void onSubjects(List<SubjectEvent> events) throws SolrServerException, IOException {
        Map<String, SubjectEvent> latest = new LinkedHashMap<>();
        for (SubjectEvent e : events) latest.put(e.subjectId().toString(), e);
        List<SolrInputDocument> docs = latest.values().stream().map(SolrDocuments::subject).toList();
        if (!docs.isEmpty()) solr.add(props.subjectsCollection(), docs, props.commitWithinMs());
    }
}
