package org.tornotron.echno_backend.modules.workprogress.domain;

/** What the inspector found for the activity on the inspection date. */
public enum ProgressOutcome {
    /** The activity is finished: 100 percent, with an actual finish date. */
    DONE,
    /** Work has happened but the activity is not finished: above 0 and below 100 percent. */
    PARTIAL,
    /** No work has been done on the activity yet. */
    NOT_DONE
}
