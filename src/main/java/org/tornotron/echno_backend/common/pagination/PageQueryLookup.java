package org.tornotron.echno_backend.common.pagination;

import org.springdoc.core.annotations.ParameterObject;

/**
 * The page pair for a lookup list: the short, organization-owned set of values a form offers in a
 * dropdown, such as work categories.
 *
 * <p>The web client loads such a list in one call and names no page size. Under the shared default
 * of {@link PageQuery#DEFAULT_PAGE_SIZE} it received the first ten entries and the dropdown silently
 * lost the rest, so a lookup endpoint serves up to {@link UnpagedResultCap#MAX_ROWS} instead. A caller
 * that pages explicitly is unaffected.
 */
@ParameterObject
public class PageQueryLookup extends PageQuery {

    /** Rows per page when the caller names none. */
    public static final int PAGE_SIZE = UnpagedResultCap.MAX_ROWS;

    public PageQueryLookup() {
        super(PAGE_SIZE);
    }
}
