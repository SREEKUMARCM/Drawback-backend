package com.drawback.review.drawback;

import com.drawback.events.DrawbackEvent;
import com.drawback.events.Topics;
import com.drawback.review.drawback.ApiModels.AttachmentDto;
import com.drawback.review.drawback.ApiModels.DrawbackDto;
import com.drawback.review.drawback.ApiModels.EditDrawbackRequest;
import com.drawback.review.drawback.ApiModels.NewDrawbackRequest;
import com.drawback.review.drawback.ApiModels.ReplyRequest;
import com.drawback.review.drawback.ApiModels.SavedResult;
import com.drawback.review.drawback.ApiModels.VoteRequest;
import com.drawback.review.drawback.ApiModels.VoteResult;
import com.drawback.review.drawback.UserSnapshotService.Snap;
import com.drawback.review.outbox.OutboxService;
import com.drawback.security.ApiException;
import com.drawback.security.AuthenticatedUser;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Write path for drawbacks, votes and replies. Same shape as the review write path:
 * cheap checks from the JWT, remote lookups OUTSIDE the transaction, then one short transaction that changes the rows,
 * applies the aggregate delta atomically and appends the outbox event.
 */
@Service
public class DrawbackService {

    private static final Set<String> SUBJECT_TYPES = Set.of(
            "product", "company", "service", "business", "app", "website", "public-service", "experience", "other");
    static final int MIN_COMMENT = 10;

    private final NamedParameterJdbcTemplate jdbc;
    private final DrawbackStore store;
    private final UserSnapshotService users;
    private final MediaService media;
    private final OutboxService outbox;
    private final TransactionTemplate tx;

    public DrawbackService(NamedParameterJdbcTemplate jdbc, DrawbackStore store, UserSnapshotService users, MediaService media,
                           OutboxService outbox, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.store = store;
        this.users = users;
        this.media = media;
        this.outbox = outbox;
        this.tx = tx;
    }

    // ---- drawbacks ------------------------------------------------------------------------------------------------

    public DrawbackDto create(NewDrawbackRequest req, AuthenticatedUser user) {
        users.requireActive(users.touch(user));
        List<AttachmentDto> attachments = media.resolve(user.userId(), req.attachments());
        String type = req.subjectType() != null && SUBJECT_TYPES.contains(req.subjectType()) ? req.subjectType() : "other";
        String visibility = "followers".equals(req.visibility()) ? "followers" : "public";

        UUID id = tx.execute(status -> {
            UUID subjectId = findSubject(req.subjectSlug()).orElseGet(
                    () -> createSubject(user.userId(), req.subjectName().trim(), blankToNull(req.brand()), type, req.category().trim()));
            UUID drawbackId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO drawback (id, author_id, subject_id, category, headline, description, location, purchase_date, reference, visibility)
                    VALUES (:id, :author, :subject, :category, :headline, :description, :location, :purchaseDate, :reference, :visibility)""",
                    new MapSqlParameterSource("id", drawbackId).addValue("author", user.userId()).addValue("subject", subjectId)
                            .addValue("category", req.category().trim()).addValue("headline", req.headline().trim())
                            .addValue("description", req.description().trim()).addValue("location", blankToNull(req.location()))
                            .addValue("purchaseDate", blankToNull(req.purchaseDate())).addValue("reference", blankToNull(req.reference()))
                            .addValue("visibility", visibility));
            int position = 0;
            for (AttachmentDto a : attachments) {
                jdbc.update("INSERT INTO drawback_attachment (id, drawback_id, position, kind, url, poster, caption, name) "
                                + "VALUES (:id, :d, :pos, :kind, :url, :poster, :caption, :name)",
                        new MapSqlParameterSource("id", UUID.fromString(a.id())).addValue("d", drawbackId).addValue("pos", position++)
                                .addValue("kind", a.kind()).addValue("url", a.url()).addValue("poster", a.poster())
                                .addValue("caption", a.caption()).addValue("name", a.name()));
            }
            return drawbackId;
        });
        return store.get(id, user.userId(), false).orElseThrow(() -> ApiException.notFound("Drawback"));
    }

    public DrawbackDto update(UUID id, EditDrawbackRequest req, AuthenticatedUser user) {
        int rows = jdbc.update("UPDATE drawback SET headline = :h, description = :d, updated_at = now() "
                        + "WHERE id = :id AND author_id = :a AND status <> 'removed'",
                new MapSqlParameterSource("id", id).addValue("a", user.userId())
                        .addValue("h", req.headline().trim()).addValue("d", req.description().trim()));
        if (rows == 0) {
            if (store.authorOf(id).isEmpty()) throw ApiException.notFound("Drawback");
            throw ApiException.forbidden("Only the author can edit this drawback.");
        }
        return store.get(id, user.userId(), false).orElseThrow(() -> ApiException.notFound("Drawback"));
    }

    public void delete(UUID id, AuthenticatedUser user) {
        UUID author = store.authorOf(id).orElseThrow(() -> ApiException.notFound("Drawback"));
        if (!author.equals(user.userId()) && !user.isStaff()) throw ApiException.forbidden("You cannot delete this drawback.");
        jdbc.update("DELETE FROM drawback WHERE id = :id", new MapSqlParameterSource("id", id)); // cascades to votes, replies, saves
    }

    public SavedResult toggleSave(UUID id, AuthenticatedUser user) {
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM drawback WHERE id = :id AND status <> 'removed'",
                new MapSqlParameterSource("id", id), Integer.class);
        if (exists == null || exists == 0) throw ApiException.notFound("Drawback");
        var p = new MapSqlParameterSource("u", user.userId()).addValue("d", id);
        if (jdbc.update("DELETE FROM saved_drawback WHERE user_id = :u AND drawback_id = :d", p) > 0) return new SavedResult(false);
        jdbc.update("INSERT INTO saved_drawback (user_id, drawback_id) VALUES (:u, :d) ON CONFLICT DO NOTHING", p);
        return new SavedResult(true);
    }

    // ---- votes & replies ------------------------------------------------------------------------------------------

    /** Creates or edits the caller's vote. One vote per user per drawback; the comment is mandatory. */
    public VoteResult submitVote(UUID drawbackId, VoteRequest req, AuthenticatedUser user) {
        if (!user.verified()) throw ApiException.forbidden("Verify your mobile number to vote.");
        String comment = req.comment().trim();
        if (comment.length() < MIN_COMMENT) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "A comment of at least " + MIN_COMMENT + " characters is required with every vote.");
        }
        Snap me = users.touch(user);
        users.requireActive(me);

        UUID voteId = tx.execute(status -> {
            var row = jdbc.query("SELECT author_id, headline FROM drawback WHERE id = :id AND status <> 'removed'",
                    new MapSqlParameterSource("id", drawbackId), (rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2)});
            if (row.isEmpty()) throw ApiException.notFound("Drawback");
            UUID author = (UUID) row.get(0)[0];
            String headline = (String) row.get(0)[1];
            if (author.equals(user.userId())) throw ApiException.forbidden("You can’t vote on your own drawback.");

            var p = new MapSqlParameterSource("d", drawbackId).addValue("a", user.userId())
                    .addValue("percent", req.percent()).addValue("comment", comment);
            // lock the caller's existing vote so two concurrent edits cannot both compute their delta from the same old value
            var existing = jdbc.query("SELECT id, percent FROM vote WHERE drawback_id = :d AND author_id = :a FOR UPDATE", p,
                    (rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getInt(2)});
            UUID id;
            int countDelta, sumDelta;
            int[] dist = new int[5];
            if (existing.isEmpty()) {
                id = UUID.randomUUID();
                int inserted = jdbc.update("INSERT INTO vote (id, drawback_id, author_id, percent, comment_text) "
                        + "VALUES (:id, :d, :a, :percent, :comment) ON CONFLICT (drawback_id, author_id) DO NOTHING", p.addValue("id", id));
                if (inserted == 0) throw ApiException.conflict("Your vote was being saved at the same time. Please try again.");
                countDelta = 1;
                sumDelta = req.percent();
                dist[bucket(req.percent())] += 1;
            } else {
                id = (UUID) existing.get(0)[0];
                int old = (Integer) existing.get(0)[1];
                jdbc.update("UPDATE vote SET percent = :percent, comment_text = :comment, updated_at = now() WHERE id = :id", p.addValue("id", id));
                countDelta = 0;
                sumDelta = req.percent() - old;
                dist[bucket(old)] -= 1;
                dist[bucket(req.percent())] += 1;
            }
            jdbc.update("""
                    UPDATE drawback SET vote_count = vote_count + :dc, vote_sum = vote_sum + :ds,
                        dist0 = dist0 + :b0, dist1 = dist1 + :b1, dist2 = dist2 + :b2, dist3 = dist3 + :b3, dist4 = dist4 + :b4,
                        updated_at = now()
                    WHERE id = :d""", new MapSqlParameterSource("d", drawbackId).addValue("dc", countDelta).addValue("ds", sumDelta)
                    .addValue("b0", dist[0]).addValue("b1", dist[1]).addValue("b2", dist[2]).addValue("b3", dist[3]).addValue("b4", dist[4]));

            if (countDelta == 1) { // a new vote notifies the author; edits do not
                publish(DrawbackEvent.Type.VOTE_CREATED, drawbackId, headline, author, me, id, req.percent(), comment);
            }
            return id;
        });
        return new VoteResult(store.vote(voteId, user.userId()).orElseThrow(), store.get(drawbackId, user.userId(), false).orElseThrow());
    }

    public VoteResult reply(UUID voteId, ReplyRequest req, AuthenticatedUser user) {
        Snap me = users.touch(user);
        users.requireActive(me);
        String text = req.text().trim();
        if (text.length() < 2) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Write a reply first.");

        UUID drawbackId = tx.execute(status -> {
            var row = jdbc.query("""
                    SELECT v.drawback_id, v.author_id, d.headline FROM vote v JOIN drawback d ON d.id = v.drawback_id
                    WHERE v.id = :id AND d.status <> 'removed'""", new MapSqlParameterSource("id", voteId),
                    (rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)});
            if (row.isEmpty()) throw ApiException.notFound("Comment");
            UUID d = (UUID) row.get(0)[0];
            UUID voteAuthor = (UUID) row.get(0)[1];
            jdbc.update("INSERT INTO vote_reply (id, vote_id, author_id, body) VALUES (:id, :v, :a, :b)",
                    new MapSqlParameterSource("id", UUID.randomUUID()).addValue("v", voteId).addValue("a", user.userId()).addValue("b", text));
            jdbc.update("UPDATE drawback SET reply_count = reply_count + 1, updated_at = now() WHERE id = :d", new MapSqlParameterSource("d", d));
            publish(DrawbackEvent.Type.REPLY_CREATED, d, (String) row.get(0)[2], voteAuthor, me, voteId, null, text);
            return d;
        });
        return new VoteResult(store.vote(voteId, user.userId()).orElseThrow(), store.get(drawbackId, user.userId(), false).orElseThrow());
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** Must run inside the transaction. No event when the actor is the recipient. */
    private void publish(DrawbackEvent.Type type, UUID drawbackId, String headline, UUID recipient, Snap actor, UUID voteId,
                         Integer percent, String text) {
        if (recipient.equals(actor.id())) return;
        String excerpt = text.length() <= 140 ? text : text.substring(0, 139) + "…";
        DrawbackEvent e = new DrawbackEvent(UUID.randomUUID(), type, Instant.now(), drawbackId, headline, recipient, actor.id(),
                actor.username(), voteId, percent, excerpt);
        outbox.enqueue(Topics.DRAWBACKS, drawbackId.toString(), e.eventId(), e);
    }

    static int bucket(int percent) {
        return percent <= 20 ? 0 : percent <= 40 ? 1 : percent <= 60 ? 2 : percent <= 80 ? 3 : 4;
    }

    private java.util.Optional<UUID> findSubject(String slug) {
        if (slug == null || slug.isBlank()) return java.util.Optional.empty();
        return jdbc.query("SELECT id FROM subject WHERE slug = :s", new MapSqlParameterSource("s", slug),
                (rs, n) -> rs.getObject(1, UUID.class)).stream().findFirst();
    }

    private UUID createSubject(UUID owner, String name, String brand, String type, String category) {
        String base = slugify(name);
        UUID id = UUID.randomUUID();
        for (int n = 1; n <= 30; n++) {
            String slug = n == 1 ? base : base + "-" + n;
            if (n > 20) slug = base + "-" + UUID.randomUUID().toString().substring(0, 6);
            int inserted = jdbc.update("""
                    INSERT INTO subject (id, slug, name, category, owner_id, brand, subject_type)
                    VALUES (:id, :slug, :name, :category, :owner, :brand, :type) ON CONFLICT (slug) DO NOTHING""",
                    new MapSqlParameterSource("id", id).addValue("slug", slug).addValue("name", name)
                            .addValue("category", category).addValue("owner", owner).addValue("brand", brand).addValue("type", type));
            if (inserted == 1) return id;
        }
        throw ApiException.conflict("Could not create the subject, please try again");
    }

    static String slugify(String name) {
        String base = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (base.length() > 48) base = base.substring(0, 48).replaceAll("-$", "");
        return base.isEmpty() ? "subject" : base;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
