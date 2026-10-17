package com.drawback.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.drawback.events.ReviewEvent;
import com.drawback.events.SubjectEvent;
import com.drawback.search.config.SolrProperties;
import com.drawback.search.index.SearchIndexer;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SearchIndexerTest {

    final SolrClient solr = mock(SolrClient.class);
    final SolrProperties props = new SolrProperties(null, "reviews", "subjects", 1000, false);
    final SearchIndexer indexer = new SearchIndexer(solr, props);

    private static ReviewEvent event(UUID reviewId, ReviewEvent.Type type, String title, int rating) {
        return new ReviewEvent(UUID.randomUUID(), type, Instant.now(), reviewId, UUID.randomUUID(), "Phone X", "smartphones",
                UUID.randomUUID(), UUID.randomUUID(), "alice", title, "body text", rating, Instant.now(), 0);
    }

    @SuppressWarnings("unchecked")
    @Test
    void batchIsCollapsedToTheLatestEventPerReviewAndWrittenInOneRequest() throws Exception {
        UUID id = UUID.randomUUID();
        var created = event(id, ReviewEvent.Type.REVIEW_CREATED, "first title", 50);
        var edited = event(id, ReviewEvent.Type.REVIEW_UPDATED, "edited title", 90);
        var other = event(UUID.randomUUID(), ReviewEvent.Type.REVIEW_CREATED, "another", 10);

        indexer.onReviews(List.of(created, edited, other));

        var docs = ArgumentCaptor.forClass(Collection.class);
        verify(solr).add(eq("reviews"), docs.capture(), eq(1000));
        Collection<SolrInputDocument> written = docs.getValue();
        assertThat(written).hasSize(2);
        var forId = written.stream().filter(d -> d.getFieldValue("id").equals(id.toString())).findFirst().orElseThrow();
        assertThat(forId.getFieldValue("title")).isEqualTo("edited title");
        assertThat(forId.getFieldValue("rating")).isEqualTo(90);
        verify(solr, never()).deleteById(eq("reviews"), org.mockito.ArgumentMatchers.<List<String>>any(), anyInt());
    }

    @Test
    void deletedEventRemovesTheDocument() throws Exception {
        UUID id = UUID.randomUUID();
        indexer.onReviews(List.of(event(id, ReviewEvent.Type.REVIEW_CREATED, "t", 10), event(id, ReviewEvent.Type.REVIEW_DELETED, "t", 10)));

        verify(solr).deleteById("reviews", List.of(id.toString()), 1000);
        verify(solr, never()).add(eq("reviews"), org.mockito.ArgumentMatchers.<Collection<SolrInputDocument>>any(), anyInt());
    }

    @Test
    void sameBatchTwiceProducesIdenticalDocuments_soRedeliveryIsHarmless() throws Exception {
        var e = event(UUID.randomUUID(), ReviewEvent.Type.REVIEW_CREATED, "t", 70);
        indexer.onReviews(List.of(e));
        indexer.onReviews(List.of(e));

        var captor = ArgumentCaptor.forClass(Collection.class);
        verify(solr, org.mockito.Mockito.times(2)).add(eq("reviews"), captor.capture(), eq(1000));
        @SuppressWarnings("unchecked") var first = (SolrInputDocument) ((Collection<SolrInputDocument>) captor.getAllValues().get(0)).iterator().next();
        @SuppressWarnings("unchecked") var second = (SolrInputDocument) ((Collection<SolrInputDocument>) captor.getAllValues().get(1)).iterator().next();
        assertThat(first.getFieldValue("id")).isEqualTo(second.getFieldValue("id"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void subjectEventsCarryAggregateAndLowercaseNameForPrefixSuggest() throws Exception {
        var s = new SubjectEvent(UUID.randomUUID(), SubjectEvent.Type.SUBJECT_STATS_CHANGED, Instant.now(), UUID.randomUUID(),
                "phone-x-1", "Phone X", "smartphones", "d", UUID.randomUUID(), 12, 71.5);

        indexer.onSubjects(List.of(s));

        var docs = ArgumentCaptor.forClass(Collection.class);
        verify(solr).add(eq("subjects"), docs.capture(), eq(1000));
        var doc = (SolrInputDocument) ((Collection<SolrInputDocument>) docs.getValue()).iterator().next();
        assertThat(doc.getFieldValue("name_lc")).isEqualTo("phone x");
        assertThat(doc.getFieldValue("review_count")).isEqualTo(12L);
        assertThat(doc.getFieldValue("avg_rating")).isEqualTo(71.5);
    }
}
