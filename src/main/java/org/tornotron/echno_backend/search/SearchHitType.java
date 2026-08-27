package org.tornotron.echno_backend.search;

/** The kind of record a {@link SearchHit} names. */
public enum SearchHitType {

    /** A project. {@code projectId} repeats the hit's own id. */
    PROJECT,

    /** A task. {@code projectId} names the project it belongs to. */
    TASK,

    /** An issue. {@code projectId} names the project of the task it was raised against. */
    ISSUE
}
