package com.drawback.review.web;

import com.drawback.review.drawback.ApiModels.CommunityStats;
import com.drawback.review.drawback.ApiModels.DrawbackDto;
import com.drawback.review.drawback.ApiModels.EditDrawbackRequest;
import com.drawback.review.drawback.ApiModels.ExploreData;
import com.drawback.review.drawback.ApiModels.NewDrawbackRequest;
import com.drawback.review.drawback.ApiModels.ReplyRequest;
import com.drawback.review.drawback.ApiModels.ReportRequest;
import com.drawback.review.drawback.ApiModels.SavedResult;
import com.drawback.review.drawback.ApiModels.SubjectDetail;
import com.drawback.review.drawback.ApiModels.SubjectWithCount;
import com.drawback.review.drawback.ApiModels.Suggestions;
import com.drawback.review.drawback.ApiModels.VoteDto;
import com.drawback.review.drawback.ApiModels.VoteRequest;
import com.drawback.review.drawback.ApiModels.VoteResult;
import com.drawback.review.drawback.CatalogService;
import com.drawback.review.drawback.DrawbackService;
import com.drawback.review.drawback.DrawbackStore;
import com.drawback.review.drawback.DrawbackStore.Filter;
import com.drawback.review.drawback.ModerationService;
import com.drawback.security.ApiException;
import com.drawback.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Drawback web app's content API. GET endpoints are public-read (guests can browse; a signed-in caller is still
 * recognised, so followers-only posts and "my vote" work). Every write requires a valid JWT.
 */
@RestController
@RequestMapping("/api/v1")
public class DrawbackController {

    private final DrawbackStore store;
    private final DrawbackService drawbacks;
    private final CatalogService catalog;
    private final ModerationService moderation;

    public DrawbackController(DrawbackStore store, DrawbackService drawbacks, CatalogService catalog, ModerationService moderation) {
        this.store = store;
        this.drawbacks = drawbacks;
        this.catalog = catalog;
        this.moderation = moderation;
    }

    // ---- reading (public) -----------------------------------------------------------------------------------------

    @GetMapping("/drawbacks")
    public List<DrawbackDto> list(@RequestParam(required = false) String tab, @RequestParam(required = false) String category,
                                  @RequestParam(required = false) String q, @RequestParam(required = false) String sort,
                                  @RequestParam(defaultValue = "false") boolean evidence, @RequestParam(defaultValue = "false") boolean verified,
                                  @RequestParam(required = false) String subject, @RequestParam(required = false) String author,
                                  @RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer offset,
                                  @AuthenticationPrincipal AuthenticatedUser viewer) {
        if ("following".equals(tab) && viewer == null) throw ApiException.unauthorized("Please sign in to continue.");
        return store.list(Filter.of(tab, category, q, sort, evidence, verified, subject, author, limit, offset), id(viewer));
    }

    @GetMapping("/drawbacks/suggestions")
    public Suggestions suggestions(@RequestParam(required = false) String q) {
        return catalog.suggestions(q);
    }

    @GetMapping("/drawbacks/{id}")
    public DrawbackDto get(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser viewer) {
        boolean staff = viewer != null && viewer.isStaff();
        return store.get(id, id(viewer), staff).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "This drawback does not exist or was removed."));
    }

    @GetMapping("/drawbacks/{id}/votes")
    public List<VoteDto> votes(@PathVariable UUID id, @RequestParam(required = false) String sort,
                               @AuthenticationPrincipal AuthenticatedUser viewer) {
        return store.votes(id, sort, id(viewer));
    }

    @GetMapping("/trending")
    public List<DrawbackDto> trending(@RequestParam(required = false) String view, @AuthenticationPrincipal AuthenticatedUser viewer) {
        return catalog.trending(view, id(viewer));
    }

    @GetMapping("/explore")
    public ExploreData explore(@AuthenticationPrincipal AuthenticatedUser viewer) {
        return catalog.explore(id(viewer));
    }

    @GetMapping("/community")
    public CommunityStats community() {
        return catalog.community();
    }

    @GetMapping("/catalog/subjects")
    public List<SubjectWithCount> subjects(@RequestParam(required = false) String q) {
        return catalog.searchSubjects(q);
    }

    @GetMapping("/catalog/subjects/{slug}")
    public SubjectDetail subject(@PathVariable String slug) {
        return catalog.subject(slug);
    }

    // ---- writing (JWT required) -----------------------------------------------------------------------------------

    @PostMapping("/drawbacks")
    @ResponseStatus(HttpStatus.CREATED)
    public DrawbackDto create(@Valid @RequestBody NewDrawbackRequest req, @AuthenticationPrincipal AuthenticatedUser user) {
        return drawbacks.create(req, user);
    }

    @PutMapping("/drawbacks/{id}")
    public DrawbackDto update(@PathVariable UUID id, @Valid @RequestBody EditDrawbackRequest req,
                              @AuthenticationPrincipal AuthenticatedUser user) {
        return drawbacks.update(id, req, user);
    }

    @DeleteMapping("/drawbacks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        drawbacks.delete(id, user);
    }

    @PostMapping("/drawbacks/{id}/save")
    public SavedResult save(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        return drawbacks.toggleSave(id, user);
    }

    @PostMapping("/drawbacks/{id}/votes")
    public VoteResult vote(@PathVariable UUID id, @Valid @RequestBody VoteRequest req, @AuthenticationPrincipal AuthenticatedUser user) {
        return drawbacks.submitVote(id, req, user);
    }

    @PostMapping("/votes/{id}/replies")
    public VoteResult reply(@PathVariable UUID id, @Valid @RequestBody ReplyRequest req, @AuthenticationPrincipal AuthenticatedUser user) {
        return drawbacks.reply(id, req, user);
    }

    @PostMapping("/reports")
    public Map<String, Boolean> report(@Valid @RequestBody ReportRequest req, @AuthenticationPrincipal AuthenticatedUser user) {
        moderation.report(req, user);
        return Map.of("ok", true);
    }

    private static UUID id(AuthenticatedUser viewer) {
        return viewer == null ? null : viewer.userId();
    }
}
