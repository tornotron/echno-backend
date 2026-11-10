package org.tornotron.echno_backend.modules.access.api;

/**
 * What the signed-in caller may do in the request's organization: the question a {@code @access.can}
 * guard asks, for a service that has to ask it part way through, such as whether an approver may
 * approve their own document. The {@code @access} bean answers it.
 *
 * <p>Asks about the area as a whole. A decision about one record of a scope-aware area goes through
 * that area's own access bean with the record's facts instead, so a grant held on one project
 * counts only for that project's records.
 *
 * <p>A permission asked only here, and by no guard, has to be listed on the asking class with
 * {@link ChecksAccess}, or the Roles screen treats it as one nothing checks.
 */
public interface CallerAccess {

    /** Whether the caller may do {@code action} in {@code area}; false without a caller. */
    boolean can(String area, String action);
}
