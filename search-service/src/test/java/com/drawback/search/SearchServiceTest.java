package com.drawback.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.drawback.search.config.SolrProperties;
import com.drawback.search.query.QueryCache;
import com.drawback.search.query.SearchDtos.ReviewQuery;
import com.drawback.search.query.SearchService;
import com.drawback.security.ApiException;
import java.util.concurrent.Executor;
import org.apache.solr.client.solrj.SolrClient;
import org.junit.jupiter.api.Test;

/** Query construction is where injection and performance mistakes happen, so it is tested directly. */
class SearchServiceTest {

    final SearchService service = new SearchService(mock(SolrClient.class),
            new SolrProperties(null, null, null, null, null), mock(QueryCache.class), (Executor) Runnable::run);

    private static ReviewQuery q(String text, String category, Integer min, String sort, int page, int size) {
        return new ReviewQuery(text, category, min, sort, page, size);
    }

    @Test
    void textQueriesUseEdismaxWithFieldBoostsAndPhraseBoost() {
        var query = service.buildReviewQuery(q("battery life", null, null, null, 0, 20));

        assertThat(query.getQuery()).isEqualTo("battery life");
        assertThat(query.get("defType")).isEqualTo("edismax");
        assertThat(query.get("qf")).contains("title^4");
        assertThat(query.get("pf")).isEqualTo("title^6");
    }

    @Test
    void blankQueryMatchesEverythingWithoutEdismax() {
        var query = service.buildReviewQuery(q("  ", null, null, null, 0, 20));
        assertThat(query.getQuery()).isEqualTo("*:*");
        assertThat(query.get("defType")).isNull();
    }

    @Test
    void categoryFilterIsPassedAsAParameterNotConcatenatedIntoTheQuery() {
        var query = service.buildReviewQuery(q("x", "smart\" OR *:*", null, null, 0, 20));

        assertThat(query.getFilterQueries()).contains("{!term f=category v=$cat}");
        assertThat(query.get("cat")).isEqualTo("smart\" or *:*");
        assertThat(String.join(" ", query.getFilterQueries())).doesNotContain("OR");
    }

    @Test
    void minRatingIsClampedToTheValidRange() {
        assertThat(service.buildReviewQuery(q("x", null, 500, null, 0, 20)).getFilterQueries()).contains("rating:[100 TO *]");
        assertThat(service.buildReviewQuery(q("x", null, -5, null, 0, 20)).getFilterQueries()).contains("rating:[0 TO *]");
    }

    @Test
    void sortIsWhitelistedAndAlwaysTieBrokenForStablePaging() {
        var query = service.buildReviewQuery(q("x", null, null, "id; DROP", 0, 20));
        assertThat(query.getSorts().get(0).getItem()).isEqualTo("score");

        var newest = service.buildReviewQuery(q("x", null, null, "newest", 0, 20));
        assertThat(newest.getSorts()).extracting(s -> s.getItem()).containsExactly("created_at", "id");
    }

    @Test
    void pageSizeIsCappedAndDeepPagingRejected() {
        assertThat(service.buildReviewQuery(q("x", null, null, null, 0, 10_000)).getRows()).isEqualTo(50);
        assertThat(service.buildReviewQuery(q("x", null, null, null, 2, 20)).getStart()).isEqualTo(40);
        assertThatThrownBy(() -> service.buildReviewQuery(q("x", null, null, null, 100_000, 50)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void highlightingIsHtmlEncodedSoSnippetsAreSafeToRender() {
        var query = service.buildReviewQuery(q("x", null, null, null, 0, 20));
        assertThat(query.getHighlight()).isTrue();
        assertThat(query.get("hl.encoder")).isEqualTo("html");
    }

    @Test
    void overlongQueriesAreTruncated() {
        var query = service.buildReviewQuery(q("a".repeat(5_000), null, null, null, 0, 20));
        assertThat(query.getQuery()).hasSize(200);
    }
}
