package org.tornotron.echno_backend.modules.inspections.events;

/** The kind of record an {@code InspectionEvent} is about. */
public enum InspectionEventSubjectType {
    INSPECTION,
    CHECK_ITEM,
    DEFECT,
    NCR,
    OBSERVATION,
    REINSPECTION
}
