package org.tornotron.echno_backend.modules.access.api;

/**
 * The access decisions, whichever engine makes them.
 *
 * <p>Three questions, and nothing about how they are answered: an engine may use roles and a
 * matrix, relationships, or policies, and callers do not change. Every engine must pass the
 * shared contract tests, which pin the rules below.
 *
 * <p>Rules every engine keeps:
 * <ul>
 *   <li>A subject who is not an active member of the organization holds nothing.
 *   <li>A record in another organization is never allowed, whatever the subject holds.
 *   <li>{@link AccessScope#RELATED} reaches records linked to the subject, never records they
 *       only created; {@link AccessScope#OWN} reaches records about the subject.
 *   <li>{@link #filter} admits a record exactly when {@link #check} on that record allows it.
 * </ul>
 *
 * <p>Answers are read on every request and every list, so an engine answers from memory or a
 * cache, never with a call per record.
 */
public interface AccessService {

    /** Whether the subject may do this somewhere in the area: on at least some records. */
    boolean check(AccessSubject subject, AccessPermission permission);

    /** Whether the subject may do this on one record. */
    boolean check(AccessSubject subject, AccessPermission permission, RecordFacts record);

    /** Which records of the area the subject may act on, for a list query. */
    AccessFilter filter(AccessSubject subject, AccessPermission permission);

    /** Everything the subject holds, for navigation and authorities. */
    AccessGrants grantsFor(AccessSubject subject);
}
