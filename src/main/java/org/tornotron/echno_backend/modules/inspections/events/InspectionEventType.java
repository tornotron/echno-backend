package org.tornotron.echno_backend.modules.inspections.events;

/**
 * The vocabulary of the inspection event log. Dot-namespaced strings rather than an enum, so a
 * new kind of event is a constant here and never a migration. The prefix names the subject the
 * event is about; the rest names what happened to it.
 */
public final class InspectionEventType {

    public static final String INSPECTION_CREATED = "inspection.created";
    public static final String INSPECTION_UPDATED = "inspection.updated";
    public static final String INSPECTION_STATUS_CHANGED = "inspection.status.changed";
    public static final String INSPECTION_RESULT_RECORDED = "inspection.result.recorded";
    public static final String INSPECTION_CANCELLED = "inspection.cancelled";
    public static final String INSPECTION_GENERATED = "inspection.generated";

    public static final String CHECK_ITEM_RESULT_RECORDED = "check_item.result.recorded";
    public static final String CHECK_ITEM_REMARKS_RECORDED = "check_item.remarks.recorded";

    public static final String DEFECT_CREATED = "defect.created";
    public static final String DEFECT_UPDATED = "defect.updated";
    public static final String DEFECT_STATUS_CHANGED = "defect.status.changed";

    public static final String NCR_CREATED = "ncr.created";
    public static final String NCR_ASSIGNED = "ncr.assigned";
    public static final String NCR_CORRECTIVE_ACTION_COMPLETE = "ncr.corrective_action.complete";
    public static final String NCR_VERIFIED = "ncr.verified";
    public static final String NCR_VERIFIED_WITHOUT_REINSPECTION = "ncr.verified.without_reinspection";
    public static final String NCR_REJECTED = "ncr.rejected";
    public static final String NCR_CLOSED = "ncr.closed";
    public static final String NCR_REOPENED = "ncr.reopened";

    public static final String OBSERVATION_CREATED = "observation.created";
    public static final String OBSERVATION_REVIEWED = "observation.reviewed";

    public static final String REINSPECTION_SCHEDULED = "reinspection.scheduled";
    public static final String REINSPECTION_OUTCOME_RECORDED = "reinspection.outcome.recorded";

    public static final String EVIDENCE_ATTACHED = "evidence.attached";
    public static final String EVIDENCE_REMOVED = "evidence.removed";

    private InspectionEventType() {
    }
}
