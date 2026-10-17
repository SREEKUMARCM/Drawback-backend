package com.drawback.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** drawback.search.solr.* */
@ConfigurationProperties(prefix = "drawback.search.solr")
public record SolrProperties(String url, String reviewsCollection, String subjectsCollection,
                             Integer commitWithinMs, Boolean initSchema) {
    public SolrProperties {
        url = url == null ? "http://localhost:8983/solr" : url;
        reviewsCollection = reviewsCollection == null ? "reviews" : reviewsCollection;
        subjectsCollection = subjectsCollection == null ? "subjects" : subjectsCollection;
        commitWithinMs = commitWithinMs == null ? 1000 : commitWithinMs;   // near-real-time: visible within ~1s
        initSchema = initSchema == null || initSchema;
    }
}
