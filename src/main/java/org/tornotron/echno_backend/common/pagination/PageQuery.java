package org.tornotron.echno_backend.common.pagination;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springdoc.core.annotations.ParameterObject;

/**
 * The {@code pageNo} and {@code pageSize} pair every paginated endpoint takes, bound once and
 * bounded once.
 *
 * <p>Both used to arrive as bare {@code int} request parameters on each handler, with nothing
 * between the caller and {@code PageRequest.of}. A page index below zero and a page size below one
 * were rejected only by {@code PageRequest} itself, deep inside the call, and a value that is not
 * a number at all failed during binding and came back as a 500. Nothing bounded the size upwards
 * at all, so {@code pageSize=1000000} was a legal request: it undoes the ceiling
 * {@link UnpagedResultCap} puts on the unpaginated listings, and on the endpoints whose mapper
 * costs a query per row it multiplies the query count rather than merely the response size.
 *
 * <p>Binding the pair as one object is what makes the bound apply everywhere rather than on the
 * handlers somebody remembered. Spring's own model-attribute binding fills it, so no custom
 * argument resolver is registered and nothing changes about resolver ordering; the constraints
 * below are checked by the same validator that checks a request body, and a breach is reported by
 * {@code GlobalExceptionHandler} as a 400 naming {@code pageNo} or {@code pageSize}. The query
 * parameters keep the names and the meaning they already had, so no caller has to change.
 *
 * <p>{@link #getPageSize()} answers the shared default. An endpoint that shipped with a different
 * default keeps it through {@link #pageSizeOr(int)}, which can tell an absent parameter from one
 * the caller set to the same value.
 */
@ParameterObject
public class PageQuery {

    /**
     * Rows per page when the caller names none.
     *
     * <p>The default the great majority of these endpoints already declared. It is a default, not
     * a limit: the limit is {@link UnpagedResultCap#MAX_ROWS}.
     */
    public static final int DEFAULT_PAGE_SIZE = 10;

    @Min(value = 0, message = "pageNo must be zero or greater")
    @Schema(description = "Zero-based page index.",
            defaultValue = "0",
            minimum = "0")
    private int pageNo = 0;

    @Min(value = 1, message = "pageSize must be at least 1")
    @Max(value = UnpagedResultCap.MAX_ROWS,
            message = "pageSize must not exceed " + UnpagedResultCap.MAX_ROWS)
    @Schema(description = "Rows per page.",
            defaultValue = "" + DEFAULT_PAGE_SIZE,
            minimum = "1",
            maximum = "" + UnpagedResultCap.MAX_ROWS)
    private int pageSize = DEFAULT_PAGE_SIZE;

    /**
     * Whether the caller sent {@code pageSize} at all.
     *
     * <p>Kept because the field carries a default, so its value alone cannot say whether it was
     * asked for. {@link #pageSizeOr(int)} needs the difference.
     */
    private boolean pageSizeGiven;

    /**
     * The requested page index, zero when the caller named none.
     *
     * @return A page index of zero or more.
     */
    public int getPageNo() {
        return pageNo;
    }

    public void setPageNo(int pageNo) {
        this.pageNo = pageNo;
    }

    /**
     * The requested page size, {@link #DEFAULT_PAGE_SIZE} when the caller named none.
     *
     * @return A page size between one and {@link UnpagedResultCap#MAX_ROWS}.
     */
    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
        this.pageSizeGiven = true;
    }

    /**
     * The requested page size, falling back to this endpoint's own default rather than the shared
     * one.
     *
     * <p>A handful of endpoints shipped with a default of twenty or thirty. Folding them onto the
     * shared default would quietly shrink the page every caller who omits the parameter already
     * receives, which is a change to a published contract and has nothing to do with the bound
     * this class exists to apply.
     *
     * @param defaultPageSize Rows per page to use when the caller sent none.
     * @return The caller's page size, or {@code defaultPageSize} if there was none.
     */
    public int pageSizeOr(int defaultPageSize) {
        return pageSizeGiven ? pageSize : defaultPageSize;
    }
}
