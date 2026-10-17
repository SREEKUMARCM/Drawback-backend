package com.drawback.review.drawback;

import com.drawback.grpc.user.UserProfile;
import com.drawback.grpc.user.UserStatus;
import com.drawback.review.client.UserDirectoryClient;
import com.drawback.review.client.UserDirectoryClient.Outcome;
import com.drawback.review.drawback.ApiModels.UserSummary;
import com.drawback.security.ApiException;
import com.drawback.security.AuthenticatedUser;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Local read model of users. Posts and votes only store the author's id; names, avatars and verification badges are
 * resolved from {@code user_snapshot}, which keeps public pages working without a token (no gRPC call needed) and keeps
 * list endpoints to one extra SQL query.
 *
 * Freshness: every authenticated request calls {@link #touch}. A row is refreshed over gRPC when it is missing, older than
 * {@code refresh-minutes}, or disagrees with the token (renamed user, newly verified phone). If auth-service is down the
 * request still proceeds, with a minimal row built from the token, and the refresh is retried a minute later.
 */
@Service
public class UserSnapshotService {

    public record Snap(UUID id, String username, String displayName, boolean verified, String avatarUrl, String bio,
                       String location, List<String> interests, String roles, Instant joinedAt, boolean suspended,
                       boolean onboarded, Instant refreshedAt) {
        public boolean staff() {
            return roles.contains("ADMIN") || roles.contains("MODERATOR");
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final UserDirectoryClient directory;
    private final long refreshMinutes;
    private final long deadlineMs;

    public UserSnapshotService(NamedParameterJdbcTemplate jdbc, UserDirectoryClient directory,
                               @Value("${drawback.review.snapshot.refresh-minutes:10}") long refreshMinutes,
                               @Value("${drawback.review.snapshot.deadline-ms:400}") long deadlineMs) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.refreshMinutes = refreshMinutes;
        this.deadlineMs = deadlineMs;
    }

    /** Makes sure the caller has an up-to-date row. Never throws because of auth-service being slow or down. */
    public Snap touch(AuthenticatedUser caller) {
        Optional<Snap> current = find(caller.userId());
        boolean fresh = current.isPresent()
                && current.get().refreshedAt().isAfter(Instant.now().minus(refreshMinutes, ChronoUnit.MINUTES))
                && current.get().username().equals(caller.username())
                && current.get().verified() == caller.verified();
        if (fresh) return current.get();

        var lookup = directory.find(caller.userId(), caller.rawToken(), deadlineMs);
        if (lookup.outcome() == Outcome.FOUND) {
            upsert(lookup.profile());
        } else if (current.isPresent()) {
            // auth-service unavailable: retry in about a minute instead of on every request
            jdbc.update("UPDATE user_snapshot SET refreshed_at = :ts WHERE id = :id",
                    new MapSqlParameterSource("id", caller.userId()).addValue("ts", retryAt()));
        } else {
            upsertMinimal(caller);
        }
        return find(caller.userId()).orElseThrow(() -> ApiException.notFound("User"));
    }

    public Optional<Snap> find(UUID id) {
        return jdbc.query("SELECT * FROM user_snapshot WHERE id = :id", new MapSqlParameterSource("id", id),
                (rs, i) -> map(rs)).stream().findFirst();
    }

    /** Case-insensitive. A viewer's token lets us ask auth-service for users this service has not seen yet. */
    public Optional<Snap> byUsername(String username, AuthenticatedUser viewerOrNull) {
        Optional<Snap> local = jdbc.query(
                "SELECT * FROM user_snapshot WHERE lower(username) = lower(:u) ORDER BY refreshed_at DESC LIMIT 1",
                new MapSqlParameterSource("u", username), (rs, i) -> map(rs)).stream().findFirst();
        if (local.isPresent() || viewerOrNull == null) return local;
        var lookup = directory.findByUsername(username, viewerOrNull.rawToken(), deadlineMs);
        if (lookup.outcome() != Outcome.FOUND) return Optional.empty();
        upsert(lookup.profile());
        return find(UUID.fromString(lookup.profile().getId()));
    }

    /** Summaries for many ids with one query. Unknown ids (never seen, deleted) get a neutral placeholder. */
    public Map<UUID, UserSummary> summaries(Collection<UUID> ids) {
        Map<UUID, UserSummary> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        jdbc.query("SELECT id, username, display_name, verified, avatar_url FROM user_snapshot WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids), rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    out.put(id, new UserSummary(id.toString(), rs.getString("username"), rs.getString("display_name"),
                            rs.getBoolean("verified"), rs.getString("avatar_url")));
                });
        for (UUID id : ids) out.putIfAbsent(id, new UserSummary(id.toString(), "member", "Member", false, null));
        return out;
    }

    public Map<UUID, Snap> snaps(Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return jdbc.query("SELECT * FROM user_snapshot WHERE id IN (:ids)", new MapSqlParameterSource("ids", ids),
                (rs, i) -> map(rs)).stream().collect(Collectors.toMap(Snap::id, s -> s));
    }

    /** Callers that write content must not be suspended (the JWT can be up to 15 minutes old). */
    public void requireActive(Snap snap) {
        if (snap.suspended()) throw ApiException.forbidden("This account is suspended.");
    }

    // ---------------------------------------------------------------------------------------------------------------

    private void upsert(UserProfile p) {
        jdbc.update("""
                INSERT INTO user_snapshot (id, username, display_name, verified, avatar_url, bio, location, interests, roles,
                                           joined_at, suspended, onboarded, refreshed_at)
                VALUES (:id, :username, :displayName, :verified, :avatar, :bio, :location, :interests, :roles,
                        :joinedAt, :suspended, :onboarded, now())
                ON CONFLICT (id) DO UPDATE SET username = EXCLUDED.username, display_name = EXCLUDED.display_name,
                    verified = EXCLUDED.verified, avatar_url = EXCLUDED.avatar_url, bio = EXCLUDED.bio,
                    location = EXCLUDED.location, interests = EXCLUDED.interests, roles = EXCLUDED.roles,
                    joined_at = EXCLUDED.joined_at, suspended = EXCLUDED.suspended, onboarded = EXCLUDED.onboarded,
                    refreshed_at = now()
                """, new MapSqlParameterSource()
                .addValue("id", UUID.fromString(p.getId()))
                .addValue("username", p.getUsername())
                .addValue("displayName", p.getDisplayName())
                .addValue("verified", p.getVerified())
                .addValue("avatar", blankToNull(p.getAvatarUrl()))
                .addValue("bio", p.getBio())
                .addValue("location", blankToNull(p.getLocation()))
                .addValue("interests", String.join(",", p.getInterestsList()))
                .addValue("roles", String.join(",", p.getRolesList()))
                .addValue("joinedAt", p.getJoinedAtEpochMs() == 0 ? null : Timestamp.from(Instant.ofEpochMilli(p.getJoinedAtEpochMs())))
                .addValue("suspended", p.getStatus() != UserStatus.ACTIVE)
                .addValue("onboarded", p.getOnboarded()));
    }

    private void upsertMinimal(AuthenticatedUser u) {
        jdbc.update("""
                INSERT INTO user_snapshot (id, username, display_name, verified, roles, refreshed_at)
                VALUES (:id, :username, :username, :verified, :roles, :ts)
                ON CONFLICT (id) DO NOTHING
                """, new MapSqlParameterSource("id", u.userId()).addValue("username", u.username())
                .addValue("verified", u.verified())
                .addValue("roles", u.roles().stream().map(Enum::name).collect(Collectors.joining(",")))
                .addValue("ts", retryAt()));
    }

    /** A refreshed_at value that makes the row count as stale again in about one minute. */
    private Timestamp retryAt() {
        return Timestamp.from(Instant.now().minus(Math.max(0, refreshMinutes - 1), ChronoUnit.MINUTES));
    }

    private static Snap map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String interests = rs.getString("interests");
        Timestamp joined = rs.getTimestamp("joined_at");
        return new Snap(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("display_name"),
                rs.getBoolean("verified"), rs.getString("avatar_url"), rs.getString("bio"), rs.getString("location"),
                interests == null || interests.isBlank() ? List.of() : List.of(interests.split(",")),
                rs.getString("roles"), joined == null ? null : joined.toInstant(), rs.getBoolean("suspended"),
                rs.getBoolean("onboarded"), rs.getTimestamp("refreshed_at").toInstant());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
