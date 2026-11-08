package org.tornotron.echno_backend.modules.inspections.api;

import java.util.List;

/**
 * The intake path, for a module that posts findings in-process: the vision job ingestion goes through
 * this, so validation, events and idempotency are the same as for an external producer.
 */
public interface ObservationIntake {

    /**
     * Each item is its own transaction, so the caller must not hold one open. The tenant must be pinned.
     *
     * @param items the findings, in order
     * @param actor who the event log names, or null to name each item's own producer
     * @return one result per item, in the same order
     */
    IntakeBatchResponse intakeAll(List<IntakeObservationRequest> items, IntakeActor actor);
}
