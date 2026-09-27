package org.tornotron.echno_backend.wbs.enums;

/**
 * How a successor activity is tied to its predecessor, in the usual scheduling vocabulary:
 * finish-to-start (the default), start-to-start, finish-to-finish and start-to-finish.
 */
public enum WbsDependencyType {
    FS,
    SS,
    FF,
    SF
}
