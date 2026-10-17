package com.drawback.review.drawback;

import com.drawback.review.drawback.ApiModels.DrawbackDto;
import com.drawback.review.drawback.ApiModels.FollowResult;
import com.drawback.review.drawback.ApiModels.Follows;
import com.drawback.review.drawback.ApiModels.ProfileComment;
import com.drawback.review.drawback.ApiModels.ProfileDetail;
import com.drawback.review.drawback.ApiModels.Settings;
import com.drawback.review.drawback.ApiModels.UserDto;
import com.drawback.review.drawback.DrawbackStore.Filter;
import com.drawback.review.drawback.UserSnapshotService.Snap;
import com.drawback.security.ApiException;
import com.drawback.security.AuthenticatedUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Follows, saved posts, per-user settings, public profiles, data export and account clean-up. */
@Service
public class SocialService {

    private final NamedParameterJdbcTemplate jdbc;
    private final DrawbackStore store;
    private final UserSnapshotService users;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public SocialService(NamedParameterJdbcTemplate jdbc, DrawbackStore store, UserSnapshotService users, ObjectMapper mapper,
                         TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.store = store;
        this.users = users;
        this.mapper = mapper;
        this.tx = tx;
    }

    // ---- follows --------------------------------------------------------------------------------------------------

    public Follows follows(UUID userId) {
        Map<String, List<String>> by = new LinkedHashMap<>();
        by.put("users", new ArrayList<>());
        by.put("categories", new ArrayList<>());
        by.put("subjects", new ArrayList<>());
        jdbc.query("SELECT kind, target_key FROM follow WHERE user_id = :u ORDER BY created_at", new MapSqlParameterSource("u", userId),
                rs -> { by.get(rs.getString(1)).add(rs.getString(2)); });
        return new Follows(by.get("users"), by.get("categories"), by.get("subjects"));
    }

    public Follows toggleFollowKey(AuthenticatedUser user, String kind, String key) {
        if (!List.of("users", "categories", "subjects").contains(kind)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown follow type");
        }
        users.touch(user);
        String normalised = normaliseKey(kind, key);
        toggle(user.userId(), kind, normalised);
        return follows(user.userId());
    }

    public FollowResult toggleFollowUser(AuthenticatedUser user, String username) {
        users.touch(user);
        Snap target = users.byUsername(username, user).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "We could not find that profile."));
        if (target.id().equals(user.userId())) throw ApiException.badRequest("You cannot follow yourself.");
        boolean nowFollowing = toggle(user.userId(), "users", target.username().toLowerCase(Locale.ROOT));
        return new FollowResult(nowFollowing, followerCount(target.username()));
    }

    /** @return true when the caller follows after the call */
    private boolean toggle(UUID userId, String kind, String key) {
        var p = new MapSqlParameterSource("u", userId).addValue("k", kind).addValue("t", key);
        if (jdbc.update("DELETE FROM follow WHERE user_id = :u AND kind = :k AND target_key = :t", p) > 0) return false;
        jdbc.update("INSERT INTO follow (user_id, kind, target_key) VALUES (:u, :k, :t) ON CONFLICT DO NOTHING", p);
        return true;
    }

    private static String normaliseKey(String kind, String key) {
        String k = key.trim();
        return "users".equals(kind) ? k.toLowerCase(Locale.ROOT) : k;
    }

    private long followerCount(String username) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM follow WHERE kind = 'users' AND target_key = :k",
                new MapSqlParameterSource("k", username.toLowerCase(Locale.ROOT)), Long.class);
        return n == null ? 0 : n;
    }

    private long followingCount(UUID userId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM follow WHERE user_id = :u AND kind = 'users'",
                new MapSqlParameterSource("u", userId), Long.class);
        return n == null ? 0 : n;
    }

    // ---- settings -------------------------------------------------------------------------------------------------

    public Settings settings(UUID userId) {
        var json = jdbc.query("SELECT json FROM user_settings WHERE user_id = :u", new MapSqlParameterSource("u", userId),
                (rs, i) -> rs.getString(1)).stream().findFirst();
        if (json.isEmpty()) return Settings.defaults();
        try {
            return mapper.readValue(json.get(), Settings.class);
        } catch (JsonProcessingException e) {
            return Settings.defaults(); // a document written by an older version must never break the page
        }
    }

    public Settings saveSettings(UUID userId, Settings s) {
        Settings clean = new Settings(
                s.privacy() == null ? Settings.defaults().privacy() : s.privacy(),
                s.notifications() == null ? Settings.defaults().notifications() : s.notifications(),
                s.content() == null ? Settings.defaults().content() : s.content(),
                s.mutedTopics() == null ? List.of() : s.mutedTopics().stream().limit(100).toList(),
                s.blocked() == null ? List.of() : s.blocked().stream().limit(500).toList());
        try {
            jdbc.update("""
                    INSERT INTO user_settings (user_id, json, updated_at) VALUES (:u, :j, now())
                    ON CONFLICT (user_id) DO UPDATE SET json = EXCLUDED.json, updated_at = now()""",
                    new MapSqlParameterSource("u", userId).addValue("j", mapper.writeValueAsString(clean)));
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("Invalid settings");
        }
        return clean;
    }

    // ---- profile --------------------------------------------------------------------------------------------------

    /** Public profile. {@code viewer} may be null (guest). */
    public ProfileDetail profile(String username, AuthenticatedUser viewer) {
        if (viewer != null) users.touch(viewer);
        Snap u = users.byUsername(username, viewer).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "We could not find that profile."));
        UUID viewerId = viewer == null ? null : viewer.userId();
        boolean isMe = u.id().equals(viewerId);
        Settings settings = settings(u.id());
        boolean activityVisible = isMe || settings.privacy().showActivity();
        boolean showLocation = isMe || settings.privacy().showLocation();

        boolean following = viewerId != null && !isMe && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT count(*) > 0 FROM follow WHERE user_id = :v AND kind = 'users' AND target_key = :k",
                new MapSqlParameterSource("v", viewerId).addValue("k", u.username().toLowerCase(Locale.ROOT)), Boolean.class));

        List<DrawbackDto> posts = store.list(Filter.of(null, null, null, "latest", false, false, null, u.username(), 50, 0), viewerId);
        List<ProfileComment> comments = activityVisible ? recentComments(u.id()) : List.of();

        UserDto dto = new UserDto(u.id().toString(), u.username(), u.displayName(), u.verified(), u.avatarUrl(), u.bio(),
                showLocation ? u.location() : null, u.joinedAt(), followerCount(u.username()), followingCount(u.id()),
                u.interests(), u.staff() ? "admin" : "user", u.onboarded());
        return new ProfileDetail(dto, following, isMe, posts, comments, activityVisible);
    }

    private List<ProfileComment> recentComments(UUID authorId) {
        return jdbc.query("""
                SELECT v.id, v.drawback_id, d.headline, v.percent, v.comment_text, v.created_at
                FROM vote v JOIN drawback d ON d.id = v.drawback_id
                WHERE v.author_id = :a AND d.status <> 'removed' AND d.visibility = 'public'
                ORDER BY v.created_at DESC LIMIT 20""", new MapSqlParameterSource("a", authorId),
                (rs, i) -> new ProfileComment(rs.getObject(1, UUID.class).toString(), rs.getObject(2, UUID.class).toString(),
                        rs.getString(3), rs.getInt(4), rs.getString(5), rs.getTimestamp(6).toInstant()));
    }

    // ---- export & deletion ----------------------------------------------------------------------------------------

    public Map<String, Object> export(AuthenticatedUser user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exportedAt", Instant.now());
        out.put("settings", settings(user.userId()));
        out.put("follows", follows(user.userId()));
        out.put("drawbacks", store.list(Filter.of(null, null, null, "latest", false, false, null, user.username(), 100, 0), user.userId()));
        out.put("votes", jdbc.query("""
                SELECT v.id, v.drawback_id, d.headline, v.percent, v.comment_text, v.created_at
                FROM vote v JOIN drawback d ON d.id = v.drawback_id WHERE v.author_id = :a ORDER BY v.created_at DESC""",
                new MapSqlParameterSource("a", user.userId()),
                (rs, i) -> new ProfileComment(rs.getObject(1, UUID.class).toString(), rs.getObject(2, UUID.class).toString(),
                        rs.getString(3), rs.getInt(4), rs.getString(5), rs.getTimestamp(6).toInstant())));
        return out;
    }

    /**
     * Removes what the user published and their social data. Votes are deleted with their aggregate contribution
     * (each vote subtracts itself from its drawback atomically), so scores stay exact.
     */
    public void purge(AuthenticatedUser user) {
        UUID id = user.userId();
        tx.executeWithoutResult(status -> {
            var votes = jdbc.query("SELECT id, drawback_id, percent FROM vote WHERE author_id = :a FOR UPDATE",
                    new MapSqlParameterSource("a", id), (rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3)});
            for (Object[] v : votes) {
                int percent = (Integer) v[2];
                int[] dist = new int[5];
                dist[DrawbackService.bucket(percent)] = -1;
                // replies under this vote disappear with it (ON DELETE CASCADE), so they leave reply_count too
                Integer replies = jdbc.queryForObject("SELECT count(*) FROM vote_reply WHERE vote_id = :v",
                        new MapSqlParameterSource("v", v[0]), Integer.class);
                jdbc.update("""
                        UPDATE drawback SET vote_count = vote_count - 1, vote_sum = vote_sum - :p, reply_count = reply_count - :r,
                            dist0 = dist0 + :b0, dist1 = dist1 + :b1, dist2 = dist2 + :b2, dist3 = dist3 + :b3, dist4 = dist4 + :b4
                        WHERE id = :d""", new MapSqlParameterSource("d", v[1]).addValue("p", percent).addValue("r", replies == null ? 0 : replies)
                        .addValue("b0", dist[0]).addValue("b1", dist[1]).addValue("b2", dist[2]).addValue("b3", dist[3]).addValue("b4", dist[4]));
                jdbc.update("DELETE FROM vote WHERE id = :v", new MapSqlParameterSource("v", v[0]));
            }
            // this user's replies under other people's votes
            var replyRows = jdbc.query("SELECT r.id, v.drawback_id FROM vote_reply r JOIN vote v ON v.id = r.vote_id WHERE r.author_id = :a",
                    new MapSqlParameterSource("a", id), (rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)});
            for (Object[] r : replyRows) {
                jdbc.update("DELETE FROM vote_reply WHERE id = :r", new MapSqlParameterSource("r", r[0]));
                jdbc.update("UPDATE drawback SET reply_count = reply_count - 1 WHERE id = :d", new MapSqlParameterSource("d", r[1]));
            }
            var p = new MapSqlParameterSource("a", id);
            jdbc.update("DELETE FROM drawback WHERE author_id = :a", p);
            jdbc.update("DELETE FROM saved_drawback WHERE user_id = :a", p);
            jdbc.update("DELETE FROM follow WHERE user_id = :a", p);
            jdbc.update("DELETE FROM user_settings WHERE user_id = :a", p);
        });
    }
}
