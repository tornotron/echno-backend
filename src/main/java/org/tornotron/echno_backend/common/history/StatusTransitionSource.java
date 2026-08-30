package org.tornotron.echno_backend.common.history;

/**
 * How a recorded status came about.
 *
 * <p>This is the column that answers the question the trail was built for. A record's status
 * alone cannot say whether the record was born in that state or moved into it later, and that
 * distinction is exactly what could not be established for the approved project with no
 * compliance inspections: whether it was created approved or patched there. {@code CREATION}
 * against {@code UPDATE} settles it.
 */
public enum StatusTransitionSource {

    /** The record was created in this status. There is no earlier status to name. */
    CREATION,

    /** The status was changed on an existing record. */
    UPDATE,

    /**
     * The status a record was observed to hold when its trail began, written once by the
     * migration that introduced the trail.
     *
     * <p>It is not a transition and claims to be none. It exists so a reader can tell a record
     * whose status has not moved since recording began from a record whose history was never
     * recorded at all. Its timestamp is the migration's, never the record's creation time:
     * dating the observed status at creation time would assert the record was created in that
     * state, which is the one claim that cannot be made about anything that predates the trail.
     */
    BASELINE
}
