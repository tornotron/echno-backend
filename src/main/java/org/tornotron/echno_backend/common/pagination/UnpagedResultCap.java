package org.tornotron.echno_backend.common.pagination;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

/**
 * Hard ceiling for the list endpoints that still answer with a bare JSON array rather than a
 * {@code Page}.
 *
 * <p>Those endpoints load every row their table holds, map every one to a DTO and serialise the
 * lot, so the response grows with the tenant's history and nothing bounds it. Batch fetching keeps
 * the query <em>count</em> flat as a result set grows but cannot bound the row <em>count</em>.
 * Capping the read fixes the unbounded part without changing the response shape, which is what
 * lets it ship ahead of the client work that moves these callers onto the paginated endpoints.
 *
 * <p>A capped response is never silently truncated. Every response carries {@link #TOTAL_HEADER}
 * with the true row count, and one that did not fit also carries {@link #CAPPED_HEADER}. The count
 * comes from the {@code Page} the service already returns, so the signal is exact rather than
 * inferred from the returned size.
 */
public final class UnpagedResultCap {

    /**
     * Most rows an uncapped list endpoint may return in one response.
     *
     * <p>Set well above any plausible reference-data table and well below the point where DTO
     * mapping and serialisation cost real memory, so it bites only where the caller should have
     * been paging anyway.
     */
    public static final int MAX_ROWS = 500;

    /** Response header carrying the true total row count, capped or not. */
    public static final String TOTAL_HEADER = "X-Total-Count";

    /** Response header present only when rows were left out because the cap was reached. */
    public static final String CAPPED_HEADER = "X-Result-Capped";

    private UnpagedResultCap() {
    }

    /**
     * The single page a capped read should request: the first {@link #MAX_ROWS} rows in the
     * repository's default order.
     *
     * @return A {@code PageRequest} for page zero sized at the cap.
     */
    public static PageRequest firstPage() {
        return PageRequest.of(0, MAX_ROWS);
    }

    /**
     * Wraps a capped read as the bare-array response its callers already expect, annotated with
     * the true total and, when rows were dropped, an explicit truncation flag.
     *
     * @param page The single page a capped read produced.
     * @param <T>  The DTO type.
     * @return 200 OK carrying the page content and the count headers.
     */
    public static <T> ResponseEntity<List<T>> respond(Page<T> page) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(TOTAL_HEADER, Long.toString(page.getTotalElements()));
        if (page.getTotalElements() > page.getNumberOfElements()) {
            headers.add(CAPPED_HEADER, "true");
        }
        return new ResponseEntity<>(page.getContent(), headers, HttpStatus.OK);
    }
}
