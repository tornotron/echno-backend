package org.tornotron.echno_backend.search;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Quick search for the web client's navigation palette.
 *
 * <p>One endpoint across projects, tasks and issues rather than one per kind, because the consumer
 * is a single search box that shows a single ranked list. Three endpoints would mean three requests
 * per keystroke and three loading states to reconcile in the browser, and would push ranking across
 * kinds back into the client. The three kinds already share one read-authorization rule, so nothing
 * is given up by serving them together.
 */
@RestController
@RequestMapping("/api/v1/search/web")
@Validated
@Tag(
        name = "Search",
        description = "Quick search across the records the web client's navigation palette can jump "
                + "to. Returns a small ranked set of matches rather than a page of a collection, "
                + "because a search box wants what matches rather than what comes first."
)
public class SearchController {

    private final SearchService searchService;

    /**
     * Constructs the controller with its service.
     *
     * @param searchService The cross-entity search.
     */
    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * Finds projects, tasks and issues matching a term.
     *
     * @param q     The search term. Anything shorter than two characters returns nothing.
     * @param limit Rows per kind, defaulting to ten and clamped to twenty-five.
     * @return The matching hits, grouped by kind and ranked within each.
     */
    @GetMapping
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant() or @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(
            summary = "Search projects, tasks and issues",
            description = "Returns records of any of the three kinds whose name contains the term, "
                    + "case-insensitively, with at most limit rows of each kind. A term shorter "
                    + "than two characters returns an empty list without querying. Each hit carries "
                    + "only its kind, id, title and owning project, which is what a navigation "
                    + "result needs to be shown and followed."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Matching hits returned, possibly none"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<SearchHit>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(searchService.search(q, limit));
    }
}
