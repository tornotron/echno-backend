package org.tornotron.echno_backend.common.pagination;

import org.springdoc.core.annotations.ParameterObject;

/**
 * The page pair for an endpoint that ships twenty rows to a caller who asks for no particular
 * number.
 *
 * <p>Several listings served twenty long before {@link PageQuery} existed, most of them because
 * they took a Spring {@code Pageable} and nothing set
 * {@code spring.data.web.pageable.default-page-size} away from it. Folding them onto
 * {@link PageQuery#DEFAULT_PAGE_SIZE} would halve what every caller who omits {@code pageSize}
 * receives today, which is a change to a published contract and no part of describing one.
 *
 * <p>The endpoint declares this type instead of writing the number in its handler, so the number
 * is here and nowhere else. {@link PageSizeSchemaCustomizer} reads it back off the declared type
 * when the document is assembled: the contract cannot say twenty while the handler serves
 * something else, because both come from the constructor below.
 */
@ParameterObject
public class PageQuery20 extends PageQuery {

    /** Rows per page when the caller names none. */
    public static final int PAGE_SIZE = 20;

    public PageQuery20() {
        super(PAGE_SIZE);
    }
}
