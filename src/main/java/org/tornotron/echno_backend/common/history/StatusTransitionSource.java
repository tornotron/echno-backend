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
     * The status was moved by the application itself, working it out from records the user
     * changed rather than from a status the user asked for.
     *
     * <p>It is separate from {@code UPDATE} because there is no actor to name. A purchase order
     * reaching {@code FULLY_RECEIVED} because the quantities received against it finally met the
     * quantities ordered is nobody's decision: the person filed a goods receipt, and the order's
     * status is arithmetic over that receipt and the ones before it. Recording it as an
     * {@code UPDATE} with a null actor would leave a reader unable to tell a transition nobody
     * made from one whose actor was lost. The document that caused it is named in the note, and
     * that document carries the person who filed it.
     */
    SYSTEM,

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
