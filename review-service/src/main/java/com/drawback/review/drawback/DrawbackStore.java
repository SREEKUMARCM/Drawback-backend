package com.drawback.review.drawback;

import com.drawback.review.drawback.ApiModels.AttachmentDto;
import com.drawback.review.drawback.ApiModels.DrawbackDto;
import com.drawback.review.drawback.ApiModels.OfficialResponseDto;
import com.drawback.review.drawback.ApiModels.ReplyDto;
import com.drawback.review.drawback.ApiModels.StatsDto;
import com.drawback.review.drawback.ApiModels.SubjectDto;
import com.drawback.review.drawback.ApiModels.UserSummary;
import com.drawback.review.drawback.ApiModels.Viewer;
import com.drawback.review.drawback.ApiModels.ViewerVote;
import com.drawback.review.drawback.ApiModels.VoteDto;
import com.drawback.review.drawback.ApiModels.VotePreview;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * All SQL for reading drawbacks and votes. Writes live in {@link DrawbackService}.
 * Plain JDBC on purpose: list endpoints need set-based joins, window functions and ranking expressions that would fight
 * an ORM, and the aggregates (vote_count, vote_sum, dist0..4) are maintained by atomic UPDATEs rather than entity state.
 */
@Repository
public class DrawbackStore {

    public static final int MIN_VOTES_FOR_RANKING = 20;

    private static final String SELECT = """
            SELECT d.id, d.author_id, d.category, d.headline, d.description, d.location, d.purchase_date, d.reference,
                   d.visibility, d.status, d.official_org, d.official_text, d.official_at,
                   d.vote_count, d.vote_sum, d.reply_count, d.dist0, d.dist1, d.dist2, d.dist3, d.dist4, d.created_at,
                   s.id AS s_id, s.slug AS s_slug, s.name AS s_name, s.brand AS s_brand, s.subject_type AS s_type,
                   s.category AS s_category, s.identity_verified AS s_idv,
                   (SELECT count(*) FROM vote gv WHERE gv.drawback_id = d.id AND gv.created_at > now() - interval '7 days') AS growth7d
            FROM drawback d
            JOIN subject s ON s.id = d.subject_id
            LEFT JOIN user_snapshot a ON a.id = d.author_id
            """;

    private static final String HAYSTACK =
            "(d.headline || ' ' || d.description || ' ' || s.name || ' ' || COALESCE(s.brand, '') || ' ' "
                    + "|| replace(d.category, '-', ' ') || ' ' || COALESCE(a.username, ''))";

    private static final String AGE_HOURS = "(extract(epoch FROM (now() - d.created_at)) / 3600.0)";
    private static final String GROWTH = "(SELECT count(*) FROM vote gv WHERE gv.drawback_id = d.id AND gv.created_at > now() - interval '7 days')";
    /** Same formula as the web app's trendingRank(): agreement is deliberately NOT an input. */
    private static final String TRENDING_RANK =
            "((ln(1.0 + d.vote_count + (d.vote_count + d.reply_count) * 1.5)::double precision * 12 + " + GROWTH + " * 0.6) "
                    + "/ power((" + AGE_HOURS + " / 24.0 + 2)::double precision, 0.35))";
    private static final String AVG = "(d.vote_sum::double precision / NULLIF(d.vote_count, 0))";

    /** Relevance of a comment: longer, discussed, recent and from a verified author ranks higher. */
    static final String VOTE_RELEVANCE =
            "(length(v.comment_text) * 0.5 + (SELECT count(*) FROM vote_reply r WHERE r.vote_id = v.id) * 30 "
                    + "- extract(epoch FROM (now() - v.created_at)) / 3600.0 * 0.15 + CASE WHEN COALESCE(u.verified, false) THEN 10 ELSE 0 END)";

    private final NamedParameterJdbcTemplate jdbc;
    private final UserSnapshotService users;

    public DrawbackStore(NamedParameterJdbcTemplate jdbc, UserSnapshotService users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    // ---- filters --------------------------------------------------------------------------------------------------

    public record Filter(String tab, String category, String q, String sort, boolean evidence, boolean verified, String subject,
                         String author, String trendingView, boolean publicOnly, int limit, int offset) {
        public static Filter of(String tab, String category, String q, String sort, boolean evidence, boolean verified,
                                String subject, String author, Integer limit, Integer offset) {
            return new Filter(tab, category, q, sort, evidence, verified, subject, author, null, false,
                    limit == null ? 50 : Math.min(Math.max(limit, 1), 100), offset == null ? 0 : Math.max(offset, 0));
        }
    }

    // ---- reads ----------------------------------------------------------------------------------------------------

    public List<DrawbackDto> list(Filter f, UUID viewer) {
        StringBuilder where = new StringBuilder(" WHERE d.status <> 'removed'");
        var p = new MapSqlParameterSource();
        if (f.publicOnly() || viewer == null) {
            where.append(" AND d.visibility = 'public'");
        } else {
            // followers-only posts are visible to their author and to people who follow the author
            where.append(" AND (d.visibility = 'public' OR d.author_id = :viewer OR EXISTS (SELECT 1 FROM follow vf "
                    + "WHERE vf.user_id = :viewer AND vf.kind = 'users' AND vf.target_key = lower(a.username)))");
            p.addValue("viewer", viewer);
        }
        if ("following".equals(f.tab()) && viewer != null) {
            where.append(" AND EXISTS (SELECT 1 FROM follow ff WHERE ff.user_id = :viewer AND ("
                    + "(ff.kind = 'users' AND ff.target_key = lower(a.username)) OR "
                    + "(ff.kind = 'categories' AND ff.target_key = d.category) OR "
                    + "(ff.kind = 'subjects' AND ff.target_key = s.slug)))");
        }
        if (notBlank(f.category())) { where.append(" AND d.category = :category"); p.addValue("category", f.category()); }
        if (notBlank(f.subject())) { where.append(" AND s.slug = :subject"); p.addValue("subject", f.subject()); }
        if (notBlank(f.author())) { where.append(" AND lower(a.username) = lower(:author)"); p.addValue("author", f.author()); }
        if (f.evidence()) where.append(" AND EXISTS (SELECT 1 FROM drawback_attachment x WHERE x.drawback_id = d.id)");
        if (f.verified()) where.append(" AND COALESCE(a.verified, false)");
        int i = 0;
        if (notBlank(f.q())) {
            for (String term : f.q().trim().toLowerCase().split("\\s+")) {
                if (term.isEmpty()) continue;
                where.append(" AND ").append(HAYSTACK).append(" ILIKE :q").append(i);
                p.addValue("q" + i, likePattern(term));
                if (++i >= 8) break; // bound the work a single request can ask for
            }
        }

        String order;
        if (f.trendingView() != null) {
            switch (f.trendingView()) {
                case "discussed" -> order = "(d.vote_count + d.reply_count) DESC, d.created_at DESC";
                case "agreement" -> {
                    where.append(" AND d.vote_count >= ").append(MIN_VOTES_FOR_RANKING);
                    order = AVG + " DESC, d.vote_count DESC";
                }
                case "week" -> order = GROWTH + " DESC, d.created_at DESC";
                case "emerging" -> {
                    where.append(" AND ").append(AGE_HOURS).append(" < 72 AND d.vote_count < 60");
                    order = GROWTH + " / (" + AGE_HOURS + " + 6) DESC, d.created_at DESC";
                }
                default -> order = TRENDING_RANK + " DESC, d.created_at DESC";
            }
        } else {
            String mode = "trending".equals(f.tab()) ? "trending" : "latest".equals(f.tab()) ? "latest"
                    : (notBlank(f.sort()) ? f.sort() : "relevance");
            order = switch (mode) {
                case "trending" -> TRENDING_RANK + " DESC, d.created_at DESC";
                case "agreement" -> "(d.vote_count >= " + MIN_VOTES_FOR_RANKING + ") DESC, COALESCE(" + AVG + ", 0) DESC, d.vote_count DESC";
                case "votes" -> "d.vote_count DESC, d.created_at DESC";
                case "discussed" -> "(d.vote_count + d.reply_count) DESC, d.created_at DESC";
                case "latest" -> "d.created_at DESC";
                case "oldest" -> "d.created_at ASC";
                default -> "(ln(1.0 + d.vote_count)::double precision * 6 - " + AGE_HOURS + " / 12.0) DESC, d.created_at DESC";
            };
        }
        p.addValue("limit", f.limit()).addValue("offset", f.offset());
        List<Row> rows = jdbc.query(SELECT + where + " ORDER BY " + order + " LIMIT :limit OFFSET :offset", p, (rs, n) -> row(rs));
        return assemble(rows, viewer);
    }

    /** One drawback by id. Removed posts are returned only when {@code includeRemoved} (moderators). */
    public Optional<DrawbackDto> get(UUID id, UUID viewer, boolean includeRemoved) {
        List<Row> rows = jdbc.query(SELECT + " WHERE d.id = :id" + (includeRemoved ? "" : " AND d.status <> 'removed'"),
                new MapSqlParameterSource("id", id), (rs, n) -> row(rs));
        if (rows.isEmpty()) return Optional.empty();
        Row r = rows.get(0);
        // followers-only posts are visible to the author and to followers only
        if ("followers".equals(r.visibility) && !includeRemoved && !canSeeFollowersOnly(r, viewer)) return Optional.empty();
        return Optional.of(assemble(rows, viewer).get(0));
    }

    public Optional<UUID> authorOf(UUID drawbackId) {
        return jdbc.query("SELECT author_id FROM drawback WHERE id = :id", new MapSqlParameterSource("id", drawbackId),
                (rs, n) -> rs.getObject(1, UUID.class)).stream().findFirst();
    }

    public List<DrawbackDto> saved(UUID viewer) {
        List<Row> rows = jdbc.query(SELECT + " JOIN saved_drawback sd ON sd.drawback_id = d.id AND sd.user_id = :viewer "
                        + "WHERE d.status <> 'removed' ORDER BY sd.created_at DESC LIMIT 100",
                new MapSqlParameterSource("viewer", viewer), (rs, n) -> row(rs));
        return assemble(rows, viewer);
    }

    public List<VoteDto> votes(UUID drawbackId, String sort, UUID viewer) {
        String order = switch (sort == null ? "relevant" : sort) {
            case "newest" -> "v.created_at DESC";
            case "highest" -> "v.percent DESC, v.created_at DESC";
            default -> VOTE_RELEVANCE + " DESC";
        };
        List<VoteRow> rows = jdbc.query("""
                SELECT v.id, v.drawback_id, v.author_id, v.percent, v.comment_text, v.created_at
                FROM vote v LEFT JOIN user_snapshot u ON u.id = v.author_id
                WHERE v.drawback_id = :d ORDER BY\s""" + order + " LIMIT 300",
                new MapSqlParameterSource("d", drawbackId), (rs, n) -> voteRow(rs));
        return toVoteDtos(rows, viewer, true);
    }

    public Optional<VoteDto> vote(UUID voteId, UUID viewer) {
        List<VoteRow> rows = jdbc.query("SELECT id, drawback_id, author_id, percent, comment_text, created_at FROM vote WHERE id = :id",
                new MapSqlParameterSource("id", voteId), (rs, n) -> voteRow(rs));
        return toVoteDtos(rows, viewer, false).stream().findFirst();
    }

    // ---- assembly -------------------------------------------------------------------------------------------------

    List<DrawbackDto> assemble(List<Row> rows, UUID viewer) {
        if (rows.isEmpty()) return List.of();
        List<UUID> ids = rows.stream().map(Row::id).toList();
        var idParam = new MapSqlParameterSource("ids", ids);

        Map<UUID, List<AttachmentDto>> attachments = new HashMap<>();
        jdbc.query("SELECT id, drawback_id, kind, url, poster, caption, name FROM drawback_attachment "
                + "WHERE drawback_id IN (:ids) ORDER BY drawback_id, position", idParam, rs -> {
                    attachments.computeIfAbsent(rs.getObject("drawback_id", UUID.class), k -> new ArrayList<>())
                            .add(new AttachmentDto(rs.getObject("id", UUID.class).toString(), rs.getString("kind"),
                                    rs.getString("url"), rs.getString("poster"), rs.getString("caption"), rs.getString("name")));
                });

        record Prev(UUID drawbackId, UUID authorId, int percent, String comment) {}
        List<Prev> previews = jdbc.query("""
                SELECT * FROM (
                  SELECT v.drawback_id, v.author_id, v.percent, v.comment_text,
                         row_number() OVER (PARTITION BY v.drawback_id ORDER BY\s""" + VOTE_RELEVANCE + """
                 DESC) AS rn
                  FROM vote v LEFT JOIN user_snapshot u ON u.id = v.author_id
                  WHERE v.drawback_id IN (:ids)) t
                WHERE rn <= 2 ORDER BY drawback_id, rn""", idParam,
                (rs, n) -> new Prev(rs.getObject("drawback_id", UUID.class), rs.getObject("author_id", UUID.class),
                        rs.getInt("percent"), rs.getString("comment_text")));

        Map<UUID, ViewerVote> mine = new HashMap<>();
        Set<UUID> saved = new HashSet<>();
        if (viewer != null) {
            var vp = new MapSqlParameterSource("ids", ids).addValue("viewer", viewer);
            jdbc.query("SELECT drawback_id, percent, comment_text FROM vote WHERE author_id = :viewer AND drawback_id IN (:ids)", vp,
                    rs -> { mine.put(rs.getObject("drawback_id", UUID.class), new ViewerVote(rs.getInt("percent"), rs.getString("comment_text"))); });
            jdbc.query("SELECT drawback_id FROM saved_drawback WHERE user_id = :viewer AND drawback_id IN (:ids)", vp,
                    rs -> { saved.add(rs.getObject("drawback_id", UUID.class)); });
        }

        Set<UUID> authorIds = new HashSet<>();
        rows.forEach(r -> authorIds.add(r.authorId));
        previews.forEach(pv -> authorIds.add(pv.authorId()));
        Map<UUID, UserSummary> authors = users.summaries(authorIds);

        Map<UUID, List<VotePreview>> previewBy = new HashMap<>();
        for (Prev pv : previews) {
            previewBy.computeIfAbsent(pv.drawbackId(), k -> new ArrayList<>())
                    .add(new VotePreview(authors.get(pv.authorId()), pv.percent(), pv.comment()));
        }

        List<DrawbackDto> out = new ArrayList<>(rows.size());
        for (Row r : rows) {
            int agreement = r.voteCount == 0 ? 0 : (int) Math.round((double) r.voteSum / r.voteCount);
            var stats = new StatsDto(r.voteCount, r.voteSum, agreement, r.voteCount + r.replyCount, r.dist, r.growth7d);
            OfficialResponseDto official = r.officialText == null ? null
                    : new OfficialResponseDto(r.officialOrg == null ? "Official response" : r.officialOrg, r.officialText,
                    r.officialAt == null ? r.createdAt : r.officialAt);
            out.add(new DrawbackDto(r.id.toString(), authors.get(r.authorId), r.subject, r.category, r.headline, r.description,
                    r.location, r.purchaseDate, r.reference, r.createdAt, attachments.getOrDefault(r.id, List.of()), r.status,
                    stats, official, previewBy.getOrDefault(r.id, List.of()),
                    new Viewer(saved.contains(r.id), viewer != null && viewer.equals(r.authorId), mine.get(r.id))));
        }
        return out;
    }

    private List<VoteDto> toVoteDtos(List<VoteRow> rows, UUID viewer, boolean ownFirst) {
        if (rows.isEmpty()) return List.of();
        List<UUID> ids = rows.stream().map(VoteRow::id).toList();
        Map<UUID, List<ReplyRow>> replies = new LinkedHashMap<>();
        jdbc.query("SELECT id, vote_id, author_id, body, created_at FROM vote_reply WHERE vote_id IN (:ids) ORDER BY created_at",
                new MapSqlParameterSource("ids", ids), rs -> {
                    replies.computeIfAbsent(rs.getObject("vote_id", UUID.class), k -> new ArrayList<>())
                            .add(new ReplyRow(rs.getObject("id", UUID.class), rs.getObject("author_id", UUID.class),
                                    rs.getString("body"), rs.getTimestamp("created_at").toInstant()));
                });
        Set<UUID> authorIds = new HashSet<>();
        rows.forEach(v -> authorIds.add(v.authorId));
        replies.values().forEach(l -> l.forEach(r -> authorIds.add(r.authorId)));
        Map<UUID, UserSummary> authors = users.summaries(authorIds);

        List<VoteDto> own = new ArrayList<>(), others = new ArrayList<>();
        for (VoteRow v : rows) {
            List<ReplyDto> rs = replies.getOrDefault(v.id, List.of()).stream()
                    .map(r -> new ReplyDto(r.id.toString(), authors.get(r.authorId), r.body, r.createdAt)).toList();
            boolean isMine = viewer != null && viewer.equals(v.authorId);
            VoteDto dto = new VoteDto(v.id.toString(), v.drawbackId.toString(), authors.get(v.authorId), v.percent, v.comment,
                    v.createdAt, rs, isMine);
            (isMine && ownFirst ? own : others).add(dto);
        }
        own.addAll(others); // the viewer's own vote always leads so they can find and edit it
        return own;
    }

    private boolean canSeeFollowersOnly(Row r, UUID viewer) {
        if (viewer == null) return false;
        if (viewer.equals(r.authorId)) return true;
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM follow f JOIN user_snapshot a ON a.id = :author
                WHERE f.user_id = :viewer AND f.kind = 'users' AND f.target_key = lower(a.username)""",
                new MapSqlParameterSource("author", r.authorId).addValue("viewer", viewer), Integer.class);
        return n != null && n > 0;
    }

    // ---- row mapping ----------------------------------------------------------------------------------------------

    record Row(UUID id, UUID authorId, String category, String headline, String description, String location,
               String purchaseDate, String reference, String visibility, String status, String officialOrg,
               String officialText, Instant officialAt, int voteCount, long voteSum, int replyCount, int[] dist,
               Instant createdAt, SubjectDto subject, int growth7d) {}

    record VoteRow(UUID id, UUID drawbackId, UUID authorId, int percent, String comment, Instant createdAt) {}

    record ReplyRow(UUID id, UUID authorId, String body, Instant createdAt) {}

    private static Row row(ResultSet rs) throws SQLException {
        Timestamp official = rs.getTimestamp("official_at");
        var subject = new SubjectDto(rs.getObject("s_id", UUID.class).toString(), rs.getString("s_slug"), rs.getString("s_name"),
                rs.getString("s_brand"), rs.getString("s_type"), rs.getString("s_category"), rs.getBoolean("s_idv"));
        return new Row(rs.getObject("id", UUID.class), rs.getObject("author_id", UUID.class), rs.getString("category"),
                rs.getString("headline"), rs.getString("description"), rs.getString("location"), rs.getString("purchase_date"),
                rs.getString("reference"), rs.getString("visibility"), rs.getString("status"), rs.getString("official_org"),
                rs.getString("official_text"), official == null ? null : official.toInstant(), rs.getInt("vote_count"),
                rs.getLong("vote_sum"), rs.getInt("reply_count"),
                new int[] {rs.getInt("dist0"), rs.getInt("dist1"), rs.getInt("dist2"), rs.getInt("dist3"), rs.getInt("dist4")},
                rs.getTimestamp("created_at").toInstant(), subject, rs.getInt("growth7d"));
    }

    private static VoteRow voteRow(ResultSet rs) throws SQLException {
        return new VoteRow(rs.getObject("id", UUID.class), rs.getObject("drawback_id", UUID.class),
                rs.getObject("author_id", UUID.class), rs.getInt("percent"), rs.getString("comment_text"),
                rs.getTimestamp("created_at").toInstant());
    }

    static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** Escapes LIKE wildcards so user input is matched literally. */
    static String likePattern(String term) {
        return "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
