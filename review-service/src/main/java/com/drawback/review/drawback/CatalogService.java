package com.drawback.review.drawback;

import static com.drawback.review.drawback.DrawbackStore.likePattern;

import com.drawback.review.drawback.ApiModels.CommunityStats;
import com.drawback.review.drawback.ApiModels.DrawbackDto;
import com.drawback.review.drawback.ApiModels.DrawbackRef;
import com.drawback.review.drawback.ApiModels.ExploreData;
import com.drawback.review.drawback.ApiModels.PopularBrand;
import com.drawback.review.drawback.ApiModels.SubjectDetail;
import com.drawback.review.drawback.ApiModels.SubjectDto;
import com.drawback.review.drawback.ApiModels.SubjectStats;
import com.drawback.review.drawback.ApiModels.SubjectWithCount;
import com.drawback.review.drawback.ApiModels.Suggestions;
import com.drawback.review.drawback.ApiModels.TrendingTopic;
import com.drawback.review.drawback.DrawbackStore.Filter;
import com.drawback.security.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** Read side that is not tied to one drawback: subject catalogue, suggestions, explore, trending, community numbers. */
@Service
public class CatalogService {

    private final NamedParameterJdbcTemplate jdbc;
    private final DrawbackStore store;

    public CatalogService(NamedParameterJdbcTemplate jdbc, DrawbackStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    public List<SubjectWithCount> searchSubjects(String q) {
        String where = q == null || q.isBlank() ? "" : " WHERE (s.name || ' ' || COALESCE(s.brand, '')) ILIKE :q";
        var p = new MapSqlParameterSource();
        if (!where.isEmpty()) p.addValue("q", likePattern(q.trim().toLowerCase()));
        return jdbc.query("""
                SELECT s.id, s.slug, s.name, s.brand, s.subject_type, s.category, s.identity_verified,
                       (SELECT count(*) FROM drawback d WHERE d.subject_id = s.id AND d.status <> 'removed' AND d.visibility = 'public') AS n
                FROM subject s""" + where + " ORDER BY n DESC, s.name LIMIT 6", p,
                (rs, i) -> new SubjectWithCount(rs.getObject("id", UUID.class).toString(), rs.getString("slug"), rs.getString("name"),
                        rs.getString("brand"), rs.getString("subject_type"), rs.getString("category"),
                        rs.getBoolean("identity_verified"), rs.getLong("n")));
    }

    public SubjectDetail subject(String slug) {
        var subject = jdbc.query("SELECT id, slug, name, brand, subject_type, category, identity_verified FROM subject WHERE slug = :s",
                new MapSqlParameterSource("s", slug), (rs, i) -> subjectRow(rs)).stream().findFirst()
                .orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "We could not find that product or subject."));
        var stats = jdbc.queryForMap("""
                SELECT count(*) AS n, COALESCE(sum(vote_count), 0) AS votes, COALESCE(sum(vote_sum), 0) AS total
                FROM drawback WHERE subject_id = :id AND status <> 'removed' AND visibility = 'public'""",
                new MapSqlParameterSource("id", UUID.fromString(subject.id())));
        long votes = ((Number) stats.get("votes")).longValue();
        long total = ((Number) stats.get("total")).longValue();
        return new SubjectDetail(subject, new SubjectStats(((Number) stats.get("n")).intValue(), votes,
                votes == 0 ? 0 : (int) Math.round((double) total / votes)));
    }

    public Suggestions suggestions(String q) {
        if (q == null || q.isBlank()) return new Suggestions(List.of(), List.of());
        String like = likePattern(q.trim().toLowerCase());
        List<SubjectDto> subjects = jdbc.query("""
                SELECT id, slug, name, brand, subject_type, category, identity_verified FROM subject
                WHERE (name || ' ' || COALESCE(brand, '')) ILIKE :q ORDER BY name LIMIT 4""",
                new MapSqlParameterSource("q", like), (rs, i) -> subjectRow(rs));
        List<DrawbackRef> drawbacks = jdbc.query("""
                SELECT d.id, d.headline FROM drawback d JOIN subject s ON s.id = d.subject_id
                WHERE d.status <> 'removed' AND d.visibility = 'public'
                  AND (d.headline || ' ' || s.name || ' ' || COALESCE(s.brand, '')) ILIKE :q
                ORDER BY d.created_at DESC LIMIT 4""", new MapSqlParameterSource("q", like),
                (rs, i) -> new DrawbackRef(rs.getObject(1, UUID.class).toString(), rs.getString(2)));
        return new Suggestions(subjects, drawbacks);
    }

    public ExploreData explore(UUID viewer) {
        Map<String, Long> categoryCounts = new LinkedHashMap<>();
        jdbc.query("SELECT category, count(*) AS n FROM drawback WHERE status <> 'removed' AND visibility = 'public' "
                + "GROUP BY category ORDER BY n DESC", rs -> { categoryCounts.put(rs.getString(1), rs.getLong(2)); });

        List<TrendingTopic> topics = jdbc.query("""
                SELECT s.name, COALESCE(sum(d.vote_count), 0) AS votes,
                       count(gv.id) AS growth
                FROM drawback d JOIN subject s ON s.id = d.subject_id
                LEFT JOIN vote gv ON gv.drawback_id = d.id AND gv.created_at > now() - interval '7 days'
                WHERE d.status <> 'removed' AND d.visibility = 'public'
                GROUP BY s.id, s.name ORDER BY growth DESC, votes DESC LIMIT 6""", new MapSqlParameterSource(),
                (rs, i) -> new TrendingTopic(rs.getString(1), rs.getString(1), rs.getLong(2)));

        List<PopularBrand> brands = jdbc.query("""
                SELECT s.brand, min(s.slug) AS slug, count(*) AS n
                FROM drawback d JOIN subject s ON s.id = d.subject_id
                WHERE d.status <> 'removed' AND d.visibility = 'public' AND s.brand IS NOT NULL
                GROUP BY s.brand ORDER BY n DESC LIMIT 8""", new MapSqlParameterSource(),
                (rs, i) -> new PopularBrand(rs.getString(1), rs.getString(2), rs.getLong(3)));

        List<DrawbackDto> discussed = store.list(new Filter(null, null, null, "discussed", false, false, null, null, null, true, 3, 0), viewer);
        List<DrawbackDto> recent = store.list(new Filter(null, null, null, "latest", false, false, null, null, null, true, 3, 0), viewer);
        return new ExploreData(categoryCounts, topics, brands, discussed, recent);
    }

    public List<DrawbackDto> trending(String view, UUID viewer) {
        String v = view == null || view.isBlank() ? "now" : view;
        return store.list(new Filter(null, null, null, null, false, false, null, null, v, false, 50, 0), viewer);
    }

    public CommunityStats community() {
        Long votes = jdbc.queryForObject("SELECT count(*) FROM vote", new MapSqlParameterSource(), Long.class);
        Long drawbacks = jdbc.queryForObject("SELECT count(*) FROM drawback WHERE status <> 'removed'", new MapSqlParameterSource(), Long.class);
        Long verified = jdbc.queryForObject("SELECT count(*) FROM user_snapshot WHERE verified", new MapSqlParameterSource(), Long.class);
        return new CommunityStats(votes == null ? 0 : votes, drawbacks == null ? 0 : drawbacks, verified == null ? 0 : verified);
    }

    private static SubjectDto subjectRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SubjectDto(rs.getObject("id", UUID.class).toString(), rs.getString("slug"), rs.getString("name"),
                rs.getString("brand"), rs.getString("subject_type"), rs.getString("category"), rs.getBoolean("identity_verified"));
    }
}
