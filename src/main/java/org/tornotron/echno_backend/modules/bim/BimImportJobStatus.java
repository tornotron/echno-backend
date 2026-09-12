package org.tornotron.echno_backend.modules.bim;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where an import job is, as written by the worker and read by the backend.
 *
 * <p>The worker owns QUEUED to RUNNING and RUNNING to DONE or FAILED. The backend owns the
 * two recoveries: a RUNNING job whose lease expired goes back to QUEUED while attempts
 * remain, and to FAILED when none do. DONE and FAILED are terminal. Contract:
 * {@code docs/specs/2026-09-13-bim-import-contract.md}.
 */
public enum BimImportJobStatus {
    QUEUED,
    RUNNING,
    DONE,
    FAILED;

    public boolean isTerminal() {
        return this == DONE || this == FAILED;
    }

    public Set<BimImportJobStatus> allowedNext() {
        return switch (this) {
            case QUEUED -> EnumSet.of(RUNNING, FAILED);
            case RUNNING -> EnumSet.of(DONE, FAILED, QUEUED);
            case DONE, FAILED -> EnumSet.noneOf(BimImportJobStatus.class);
        };
    }

    public boolean canTransitionTo(BimImportJobStatus next) {
        return allowedNext().contains(next);
    }
}
