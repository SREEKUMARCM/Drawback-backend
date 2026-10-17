package com.drawback.search.index;

import com.drawback.search.config.SolrProperties;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Idempotently declares the fields we index through the Schema API (collections are pre-created from Solr's _default
 * configset, see deploy/docker/docker-compose.yml). Explicit fields mean no schemaless guessing in production.
 */
@Component
public class SolrSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SolrSchemaInitializer.class);

    private final SolrClient solr;
    private final SolrProperties props;

    public SolrSchemaInitializer(SolrClient solr, SolrProperties props) {
        this.solr = solr;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!props.initSchema()) return;
        ensure(props.reviewsCollection(), new String[][]{
                {"subject_id", "string"}, {"subject_name", "text_general"}, {"category", "string"},
                {"author_id", "string"}, {"author_name", "text_general"}, {"title", "text_general"},
                {"body", "text_general"}, {"rating", "pint"}, {"created_at", "pdate"}, {"review_version", "plong"}});
        ensure(props.subjectsCollection(), new String[][]{
                {"slug", "string"}, {"name", "text_general"}, {"name_lc", "string"}, {"category", "string"},
                {"description", "text_general"}, {"owner_id", "string"}, {"review_count", "plong"}, {"avg_rating", "pdouble"}});
    }

    @SuppressWarnings("unchecked")
    private void ensure(String collection, String[][] fields) throws Exception {
        var existing = new SchemaRequest.Fields().process(solr, collection).getFields();
        Set<String> names = new HashSet<>();
        for (Map<String, Object> f : existing) names.add((String) f.get("name"));
        for (String[] f : fields) {
            if (names.contains(f[0])) continue;
            Map<String, Object> def = Map.of("name", f[0], "type", f[1], "stored", true, "indexed", true);
            new SchemaRequest.AddField(def).process(solr, collection);
            log.info("Solr {}: added field {}:{}", collection, f[0], f[1]);
        }
    }
}
