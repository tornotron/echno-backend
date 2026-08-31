package org.tornotron.echno_backend.common.pagination;

import org.springdoc.core.annotations.ParameterObject;

/**
 * The page pair for the chat message listing, which ships thirty rows to a caller who asks for no
 * particular number.
 *
 * <p>One endpoint uses it, and it is the widest gap the document carried: three times the ten it
 * published. A room's history is read newest first in blocks, so thirty is the size the client
 * scrolls by; shrinking it would change what an existing reader receives per request.
 *
 * <p>Same arrangement as {@link PageQuery20}: the number lives in the constructor, and
 * {@link PageSizeSchemaCustomizer} publishes that number rather than a second copy of it.
 */
@ParameterObject
public class PageQuery30 extends PageQuery {

    /** Rows per page when the caller names none. */
    public static final int PAGE_SIZE = 30;

    public PageQuery30() {
        super(PAGE_SIZE);
    }
}
