package com.drawback.review.drawback;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JSON contract of the Drawback web app (see drawback-web/src/types). Field names are the web app's, on purpose:
 * the frontend needs no adapter for these endpoints. Null fields are omitted (spring.jackson.default-property-inclusion).
 */
public final class ApiModels {
    private ApiModels() {}

    // ---- output ---------------------------------------------------------------------------------------------------

    public record UserSummary(String id, String username, String displayName, boolean phoneVerified, String avatarUrl) {}

    public record UserDto(String id, String username, String displayName, boolean phoneVerified, String avatarUrl, String bio,
                          String location, Instant joinedAt, long followers, long following, List<String> interests,
                          String role, boolean onboarded) {}

    public record AttachmentDto(String id, String kind, String url, String poster, String caption, String name) {}

    public record SubjectDto(String id, String slug, String name, String brand, String type, String category,
                             boolean identityVerified) {}

    public record SubjectWithCount(String id, String slug, String name, String brand, String type, String category,
                                   boolean identityVerified, long drawbackCount) {}

    public record StatsDto(int voteCount, long voteSum, int agreement, int commentCount, int[] distribution, int growth7d) {}

    public record OfficialResponseDto(String organization, String text, Instant createdAt) {}

    public record VotePreview(UserSummary author, int percent, String comment) {}

    public record ViewerVote(int percent, String comment) {}

    public record Viewer(boolean saved, @JsonProperty("isOwner") boolean isOwner, ViewerVote vote) {}

    public record DrawbackDto(String id, UserSummary author, SubjectDto subject, String category, String headline,
                              String description, String location, String purchaseDate, String reference, Instant createdAt,
                              List<AttachmentDto> attachments, String status, StatsDto stats, OfficialResponseDto officialResponse,
                              List<VotePreview> preview, Viewer viewer) {}

    public record ReplyDto(String id, UserSummary author, String text, Instant createdAt) {}

    public record VoteDto(String id, String drawbackId, UserSummary author, int percent, String comment, Instant createdAt,
                          List<ReplyDto> replies, @JsonProperty("isMine") boolean isMine) {}

    public record VoteResult(VoteDto vote, DrawbackDto drawback) {}

    public record SubjectStats(int drawbackCount, long voteCount, int avgAgreement) {}

    public record SubjectDetail(SubjectDto subject, SubjectStats stats) {}

    public record Suggestions(List<SubjectDto> subjects, List<DrawbackRef> drawbacks) {}

    public record DrawbackRef(String id, String headline) {}

    public record TrendingTopic(String label, String q, long count) {}

    public record PopularBrand(String name, String slug, long drawbacks) {}

    public record ExploreData(Map<String, Long> categoryCounts, List<TrendingTopic> trendingTopics, List<PopularBrand> popularBrands,
                              List<DrawbackDto> mostDiscussed, List<DrawbackDto> recent) {}

    public record CommunityStats(long votes, long drawbacks, long verifiedUsers) {}

    public record ProfileComment(String id, String drawbackId, String headline, int percent, String comment, Instant createdAt) {}

    public record ProfileDetail(UserDto user, @JsonProperty("isFollowing") boolean isFollowing, @JsonProperty("isMe") boolean isMe,
                                List<DrawbackDto> posts,
                                List<ProfileComment> comments, boolean activityVisible) {}

    public record FollowResult(boolean following, long followers) {}

    public record Follows(List<String> users, List<String> categories, List<String> subjects) {}

    public record SavedResult(boolean saved) {}

    public record CaseLogEntry(String id, Instant at, String actor, String action, String note) {}

    public record ModerationCase(String id, String targetType, String drawbackId, String headline, String reason, int reports,
                                 String status, String priority, Instant createdAt, List<String> signals, List<CaseLogEntry> log) {}

    public record CaseDetail(@JsonProperty("case") ModerationCase moderationCase, DrawbackDto drawback, int[] voteTimeline,
                             String authorStatus) {}

    public record AdminStats(long users, long verifiedUsers, long drawbacks, long votes, long comments, long reportedPosts,
                             long pendingCases) {}

    // ---- settings (stored as one JSON document per user) ----------------------------------------------------------

    public record Settings(Privacy privacy, Notifications notifications, Content content, List<String> mutedTopics,
                           List<UserSummary> blocked) {
        public static Settings defaults() {
            return new Settings(new Privacy(true, true, true),
                    new Notifications(true, true, true, true, false, false), new Content(false, true), List.of(), List.of());
        }
    }

    public record Privacy(boolean showActivity, boolean showLocation, boolean discoverable) {}

    public record Notifications(boolean comments, boolean replies, boolean trending, boolean follows, boolean categories,
                                boolean email) {}

    public record Content(boolean autoplay, boolean blurSensitive) {}

    // ---- input ----------------------------------------------------------------------------------------------------

    public record AttachmentInput(@NotBlank String id, @Size(max = 200) String caption) {}

    public record NewDrawbackRequest(
            @NotBlank @Size(min = 2, max = 160) String subjectName,
            @Size(max = 200) String subjectSlug,
            @Size(max = 30) String subjectType,
            @Size(max = 120) String brand,
            @NotBlank @Size(max = 60) String category,
            @NotBlank @Size(min = 8, max = 200) String headline,
            @NotBlank @Size(min = 20, max = 8000) String description,
            @Size(max = 120) String location,
            @Size(max = 40) String purchaseDate,
            @Size(max = 160) String reference,
            @Size(max = 12) String visibility,
            @Size(max = 10) List<@Valid AttachmentInput> attachments) {}

    public record EditDrawbackRequest(
            @NotBlank @Size(min = 8, max = 200) String headline,
            @NotBlank @Size(min = 20, max = 8000) String description) {}

    public record VoteRequest(
            @Min(0) @Max(100) int percent,
            @NotBlank @Size(min = 10, max = 2000, message = "A comment of at least 10 characters is required with every vote.") String comment) {}

    public record ReplyRequest(@NotBlank @Size(min = 2, max = 600) String text) {}

    public record FollowToggle(@NotBlank String kind, @NotBlank @Size(max = 120) String key) {}

    public record ReportRequest(@NotBlank String targetType, @NotBlank String targetId, @NotBlank @Size(max = 120) String reason,
                                @Size(max = 1000) String details) {}

    public record DecisionRequest(@NotBlank String action, @NotBlank @Size(max = 1000) String note) {}
}
