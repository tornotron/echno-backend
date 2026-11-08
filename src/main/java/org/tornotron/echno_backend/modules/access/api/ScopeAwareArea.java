package org.tornotron.echno_backend.modules.access.api;

/**
 * Declares that an area applies {@link AccessService#filter} in its list and get queries, so a
 * grant on it can carry a scope other than {@link AccessScope#ALL}. An area registers by
 * declaring a bean of this type.
 *
 * <p>A grant with a narrower scope on an area that does not register would read as all records,
 * since nothing narrows its queries, so the Roles &amp; Permissions screen refuses one.
 */
public interface ScopeAwareArea {

    /** The area's id, the part before the colon in its permissions. */
    String area();
}
