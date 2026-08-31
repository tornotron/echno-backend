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
 * <p>The rows a caller gets when they name no {@code pageSize} is not one number across the API:
 * most endpoints serve {@link #DEFAULT_PAGE_SIZE}, a handful shipped with twenty, and the chat
 * message listing shipped with thirty. An endpoint keeps the default it shipped with by declaring
 * the subtype that carries it, {@link PageQuery20} or {@link PageQuery30}, in place of this class.
 * The number then exists once, in that subtype's constructor, and
 * {@link PageSizeSchemaCustomizer} reads it back off the declared type when the document is
 * assembled, so what the contract publishes is the value the handler will actually be given
 * rather than a second copy of it written by hand.
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

    // No defaultValue is declared here on purpose. It would have to be one number for every
    // endpoint that takes the pair, and twelve of them do not serve that number, so the one
    // written here would be wrong wherever it was not also the endpoint's. PageSizeSchemaCustomizer
    // writes the default onto each operation from the type that operation declares, which is the
    // same value binding will hand the handler.
    @Min(value = 1, message = "pageSize must be at least 1")
    @Max(value = UnpagedResultCap.MAX_ROWS,
            message = "pageSize must not exceed " + UnpagedResultCap.MAX_ROWS)
    @Schema(description = "Rows per page.",
            minimum = "1",
            maximum = "" + UnpagedResultCap.MAX_ROWS)
    private int pageSize;

    /**
     * Takes the shared default of {@link #DEFAULT_PAGE_SIZE} rows.
     *
     * <p>Public and argument-free because Spring's model-attribute binding constructs the declared
     * parameter type this way.
     */
    public PageQuery() {
        this(DEFAULT_PAGE_SIZE);
    }

    /**
     * Takes the default a subtype exists to carry.
     *
     * @param defaultPageSize Rows per page when the caller names none. The one place this
     *                        endpoint's default is written down.
     */
    protected PageQuery(int defaultPageSize) {
        this.pageSize = defaultPageSize;
    }

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
     * The requested page size, this endpoint's own default when the caller named none.
     *
     * @return A page size between one and {@link UnpagedResultCap#MAX_ROWS}.
     */
    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }
}
