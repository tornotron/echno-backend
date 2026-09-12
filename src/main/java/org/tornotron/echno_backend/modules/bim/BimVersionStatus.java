package org.tornotron.echno_backend.modules.bim;

import java.util.EnumSet;
import java.util.Set;

/**
 * The life of one uploaded IFC. Written only by the backend; the worker never touches a
 * version row. UPLOADED is the registered file with no job yet, QUEUED and PROCESSING mirror
 * the job, INGESTING is the backend reading the worker's output into {@code bim_elements},
 * READY is servable, FAILED carries the error and can be retried into QUEUED.
 */
public enum BimVersionStatus {
    UPLOADED,
    QUEUED,
    PROCESSING,
    INGESTING,
    READY,
    FAILED;

    public Set<BimVersionStatus> allowedNext() {
        return switch (this) {
            case UPLOADED -> EnumSet.of(QUEUED, FAILED);
            case QUEUED -> EnumSet.of(PROCESSING, FAILED);
            case PROCESSING -> EnumSet.of(INGESTING, QUEUED, FAILED);
            case INGESTING -> EnumSet.of(READY, FAILED);
            case READY -> EnumSet.noneOf(BimVersionStatus.class);
            case FAILED -> EnumSet.of(QUEUED);
        };
    }

    public boolean canTransitionTo(BimVersionStatus next) {
        return allowedNext().contains(next);
    }
}
