package org.tornotron.echno_backend.search;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Quick search across the records the navigation palette can jump to.
 *
 * <p>Answers the question a search box actually asks, which is "what matches this", not "give me
 * the first page of everything". The palette used to be fed by whole-collection reads that the
 * browser then filtered, so it could only find what had already been downloaded and it downloaded
 * the collections whether or not anyone opened it. Matching in the database instead makes the
 * result independent of what the client happens to be holding, and makes the response a few tens
 * of rows regardless of how much history the tenant has.
 */
@Service
public class SearchService {

    /**
     * Shortest term worth running. A single character matches most of a tenant's records, which
     * costs a full scan to produce a result nobody can use.
     */
    public static final int MIN_TERM_LENGTH = 2;

    /** Default rows per kind when the caller does not ask for a specific limit. */
    public static final int DEFAULT_LIMIT = 10;

    /**
     * Most rows per kind a caller may ask for. A palette shows a couple of screens at most, and the
     * ceiling is what stops this endpoint being turned back into the bulk read it replaced.
     */
    public static final int MAX_LIMIT = 25;

    private final SearchRepository searchRepository;

    /**
     * Constructs the service with its read model.
     *
     * @param searchRepository The cross-entity search queries.
     */
    public SearchService(SearchRepository searchRepository) {
        this.searchRepository = searchRepository;
    }

    /**
     * Finds projects, tasks and issues whose name matches the term.
     *
     * <p>Hits come back grouped by kind, projects first, and ranked shortest name first within each
     * kind. A term shorter than {@link #MIN_TERM_LENGTH} returns nothing without touching the
     * database, so an empty search box costs no query.
     *
     * @param term  The raw search term as the user typed it.
     * @param limit Rows per kind, clamped to {@link #MAX_LIMIT}.
     * @return Matching hits, at most {@code limit} of each kind.
     */
    @Transactional(readOnly = true)
    public List<SearchHit> search(String term, Integer limit) {
        String pattern = searchPattern(term);
        if (pattern == null) {
            return List.of();
        }

        Pageable rows = PageRequest.ofSize(
                Math.clamp(limit == null ? DEFAULT_LIMIT : limit, 1, MAX_LIMIT));

        List<SearchHit> hits = new ArrayList<>();
        hits.addAll(searchRepository.findProjects(pattern, rows));
        hits.addAll(searchRepository.findTasks(pattern, rows));
        hits.addAll(searchRepository.findIssues(pattern, rows));
        return hits;
    }

    /**
     * Builds the lower-cased {@code %...%} LIKE pattern for a term, or null when the term is too
     * short to run.
     *
     * <p>Wildcards the user typed are escaped, so searching for a per-cent sign finds records
     * containing one rather than every record in the tenant.
     *
     * @param term The raw search term.
     * @return The LIKE pattern, or null when there is nothing worth searching for.
     */
    private static String searchPattern(String term) {
        if (term == null) {
            return null;
        }
        String trimmed = term.trim();
        if (trimmed.length() < MIN_TERM_LENGTH) {
            return null;
        }
        String escaped = trimmed.toLowerCase()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
