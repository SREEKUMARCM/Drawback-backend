package com.drawback.review.drawback;

import com.drawback.review.drawback.ApiModels.AdminStats;
import com.drawback.review.drawback.ApiModels.CaseDetail;
import com.drawback.review.drawback.ApiModels.CaseLogEntry;
import com.drawback.review.drawback.ApiModels.ModerationCase;
import com.drawback.review.drawback.ApiModels.ReportRequest;
import com.drawback.review.drawback.ApiModels.DrawbackDto;
import com.drawback.security.ApiException;
import com.drawback.security.AuthenticatedUser;
import com.drawback.security.Role;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** User reports become cases; moderators resolve them. Every decision is written to the case log. */
@Service
public class ModerationService {

    private static final Map<String, String> ACTIONS = Map.of(
            "dismiss", "Dismissed", "mark_disputed", "Marked as disputed", "remove", "Content removed",
            "restore", "Content restored", "suspend_author", "Author suspended");

    private final NamedParameterJdbcTemplate jdbc;
    private final DrawbackStore store;
    private final UserSnapshotService users;
    private final AuthAdminClient authAdmin;
    private final TransactionTemplate tx;

    public ModerationService(NamedParameterJdbcTemplate jdbc, DrawbackStore store, UserSnapshotService users,
                             AuthAdminClient authAdmin, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.store = store;
        this.users = users;
        this.authAdmin = authAdmin;
        this.tx = tx;
    }

    // ---- reporting ------------------------------------------------------------------------------------------------

    public void report(ReportRequest req, AuthenticatedUser reporter) {
        users.requireActive(users.touch(reporter));
        UUID drawbackId;
        UUID voteId = null;
        String type;
        switch (req.targetType()) {
            case "post" -> { drawbackId = parse(req.targetId()); type = "post"; }
            case "comment" -> {
                voteId = parse(req.targetId());
                drawbackId = jdbc.query("SELECT drawback_id FROM vote WHERE id = :id", new MapSqlParameterSource("id", voteId),
                        (rs, i) -> rs.getObject(1, UUID.class)).stream().findFirst().orElseThrow(this::nothingToReport);
                type = "comment";
            }
            case "user" -> {
                // a user report is reviewed through that person's most recent post
                drawbackId = jdbc.query("""
                        SELECT d.id FROM drawback d JOIN user_snapshot a ON a.id = d.author_id
                        WHERE lower(a.username) = lower(:u) AND d.status <> 'removed' ORDER BY d.created_at DESC LIMIT 1""",
                        new MapSqlParameterSource("u", req.targetId()), (rs, i) -> rs.getObject(1, UUID.class)).stream().findFirst()
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "This user has no posts to review yet."));
                type = "post";
            }
            default -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown report type");
        }
        String headline = jdbc.query("SELECT headline FROM drawback WHERE id = :id AND status <> 'removed'",
                new MapSqlParameterSource("id", drawbackId), (rs, i) -> rs.getString(1)).stream().findFirst()
                .orElseThrow(this::nothingToReport);

        final UUID vote = voteId;
        tx.executeWithoutResult(status -> {
            var p = new MapSqlParameterSource("d", drawbackId).addValue("t", type).addValue("r", req.reason().trim());
            var open = jdbc.query("SELECT id, reports FROM report_case WHERE drawback_id = :d AND target_type = :t AND reason = :r "
                            + "AND status <> 'resolved' ORDER BY created_at DESC LIMIT 1 FOR UPDATE", p,
                    (rs, i) -> new Object[] {rs.getString(1), rs.getInt(2)});
            if (!open.isEmpty()) {
                int reports = (Integer) open.get(0)[1] + 1;
                jdbc.update("UPDATE report_case SET reports = :n, priority = :prio WHERE id = :id",
                        new MapSqlParameterSource("n", reports).addValue("prio", reports >= 5 ? "high" : reports >= 3 ? "medium" : "low")
                                .addValue("id", open.get(0)[0]));
                return;
            }
            String id = jdbc.queryForObject("""
                    INSERT INTO report_case (target_type, drawback_id, vote_id, headline, reason)
                    VALUES (:t, :d, :v, :h, :r) RETURNING id""", p.addValue("v", vote).addValue("h", headline), String.class);
            log(id, "system", "Case opened", "Created from a user report.");
        });
    }

    // ---- moderator views ------------------------------------------------------------------------------------------

    public AdminStats stats() {
        Map<String, Object> m = jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM user_snapshot) AS users,
                       (SELECT count(*) FROM user_snapshot WHERE verified) AS verified,
                       (SELECT count(*) FROM drawback) AS drawbacks,
                       (SELECT count(*) FROM vote) AS votes,
                       (SELECT count(*) FROM vote) + (SELECT count(*) FROM vote_reply) AS comments,
                       (SELECT count(DISTINCT drawback_id) FROM report_case) AS reported,
                       (SELECT count(*) FROM report_case WHERE status <> 'resolved') AS pending""", new MapSqlParameterSource());
        return new AdminStats(n(m, "users"), n(m, "verified"), n(m, "drawbacks"), n(m, "votes"), n(m, "comments"),
                n(m, "reported"), n(m, "pending"));
    }

    public List<ModerationCase> cases(String status) {
        var p = new MapSqlParameterSource();
        String where = "";
        if (status != null && !status.isBlank() && !"all".equals(status)) {
            where = " WHERE status = :s";
            p.addValue("s", status);
        }
        List<ModerationCase> list = jdbc.query("SELECT * FROM report_case" + where + """
                 ORDER BY (status = 'resolved'), CASE priority WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END, created_at DESC
                 LIMIT 200""", p, (rs, i) -> caseRow(rs, List.of()));
        return list;
    }

    public CaseDetail caseDetail(String id, AuthenticatedUser viewer) {
        ModerationCase c = loadCase(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Case not found."));
        UUID drawbackId = UUID.fromString(c.drawbackId());
        DrawbackDto drawback = store.get(drawbackId, viewer.userId(), true).orElse(null);

        int[] buckets = new int[12];
        if (drawback != null) {
            long start = drawback.createdAt().toEpochMilli();
            long span = Math.max(1, Instant.now().toEpochMilli() - start);
            jdbc.query("SELECT created_at FROM vote WHERE drawback_id = :d", new MapSqlParameterSource("d", drawbackId), rs -> {
                long t = rs.getTimestamp(1).getTime();
                int idx = (int) Math.min(11, Math.max(0, Math.floor((double) (t - start) / span * 12)));
                buckets[idx]++;
            });
        }
        String authorStatus = "unknown";
        if (drawback != null) {
            authorStatus = users.find(UUID.fromString(drawback.author().id())).map(s -> s.suspended() ? "suspended" : "active").orElse("active");
        }
        return new CaseDetail(c, drawback, buckets, authorStatus);
    }

    public ModerationCase decide(String id, String action, String note, AuthenticatedUser moderator) {
        String label = ACTIONS.get(action);
        if (label == null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown moderation action.");
        if (note == null || note.isBlank()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Record a note explaining the decision.");
        ModerationCase c = loadCase(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Case not found."));
        UUID drawbackId = UUID.fromString(c.drawbackId());

        if ("suspend_author".equals(action)) {
            if (!moderator.hasRole(Role.ADMIN)) throw ApiException.forbidden("Only an administrator can suspend an account.");
            Optional<UUID> author = store.authorOf(drawbackId);
            if (author.isPresent()) authAdmin.suspend(author.get(), moderator.rawToken()); // outside the DB transaction
        }
        tx.executeWithoutResult(status -> {
            String newStatus = switch (action) {
                case "remove" -> "removed";
                case "restore" -> "active";
                case "mark_disputed" -> "disputed";
                default -> null;
            };
            if (newStatus != null) {
                jdbc.update("UPDATE drawback SET status = :s, updated_at = now() WHERE id = :d",
                        new MapSqlParameterSource("s", newStatus).addValue("d", drawbackId));
            }
            if ("suspend_author".equals(action)) {
                store.authorOf(drawbackId).ifPresent(a -> jdbc.update("UPDATE user_snapshot SET suspended = true WHERE id = :a",
                        new MapSqlParameterSource("a", a)));
            }
            jdbc.update("UPDATE report_case SET status = 'resolved' WHERE id = :id", new MapSqlParameterSource("id", id));
            log(id, moderator.username(), label, note.trim());
        });
        return loadCase(id).orElseThrow();
    }

    // ---------------------------------------------------------------------------------------------------------------

    private void log(String caseId, String actor, String action, String note) {
        jdbc.update("INSERT INTO report_case_log (id, case_id, actor, action, note) VALUES (:id, :c, :a, :x, :n)",
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("c", caseId).addValue("a", actor)
                        .addValue("x", action).addValue("n", note));
    }

    private Optional<ModerationCase> loadCase(String id) {
        List<CaseLogEntry> log = jdbc.query("SELECT id, at, actor, action, note FROM report_case_log WHERE case_id = :c ORDER BY at",
                new MapSqlParameterSource("c", id),
                (rs, i) -> new CaseLogEntry(rs.getObject(1, UUID.class).toString(), rs.getTimestamp(2).toInstant(), rs.getString(3),
                        rs.getString(4), rs.getString(5)));
        return jdbc.query("SELECT * FROM report_case WHERE id = :id", new MapSqlParameterSource("id", id),
                (rs, i) -> caseRow(rs, log)).stream().findFirst();
    }

    private static ModerationCase caseRow(java.sql.ResultSet rs, List<CaseLogEntry> log) throws java.sql.SQLException {
        String signals = rs.getString("signals");
        List<String> signalList = signals == null || signals.isBlank() ? List.of() : new ArrayList<>(Arrays.asList(signals.split("\n")));
        return new ModerationCase(rs.getString("id"), rs.getString("target_type"), rs.getObject("drawback_id", UUID.class).toString(),
                rs.getString("headline"), rs.getString("reason"), rs.getInt("reports"), rs.getString("status"),
                rs.getString("priority"), rs.getTimestamp("created_at").toInstant(), signalList, log);
    }

    private ApiException nothingToReport() {
        return new ApiException(HttpStatus.NOT_FOUND, "We could not find what you are reporting.");
    }

    private static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "We could not find what you are reporting.");
        }
    }

    private static long n(Map<String, Object> m, String key) {
        return ((Number) m.get(key)).longValue();
    }
}
