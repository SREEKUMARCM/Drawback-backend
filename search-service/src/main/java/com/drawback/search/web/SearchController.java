package com.drawback.search.web;

import com.drawback.search.query.SearchDtos.GlobalSearchResult;
import com.drawback.search.query.SearchDtos.ReviewQuery;
import com.drawback.search.query.SearchDtos.ReviewSearchResult;
import com.drawback.search.query.SearchDtos.Suggestion;
import com.drawback.search.query.SearchService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** All endpoints require a valid JWT (enforced by the shared security filter chain). */
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final SearchService search;

    public SearchController(SearchService search) {
        this.search = search;
    }

    @GetMapping
    public GlobalSearchResult global(@RequestParam(defaultValue = "") String q) {
        return search.searchAll(q);
    }

    @GetMapping("/reviews")
    public ReviewSearchResult reviews(@RequestParam(defaultValue = "") String q,
                                      @RequestParam(required = false) String category,
                                      @RequestParam(required = false) Integer minRating,
                                      @RequestParam(defaultValue = "relevance") String sort,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return search.searchReviews(new ReviewQuery(q, category, minRating, sort, page, size));
    }

    @GetMapping("/suggest")
    public List<Suggestion> suggest(@RequestParam String q) {
        return search.suggest(q);
    }
}
