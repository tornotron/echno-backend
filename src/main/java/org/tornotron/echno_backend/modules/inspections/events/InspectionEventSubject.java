package org.tornotron.echno_backend.modules.inspections.events;

import org.tornotron.echno_backend.modules.inspections.domain.Inspection;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionCheckItem;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionDefect;
import org.tornotron.echno_backend.modules.inspections.domain.Ncr;

import java.util.Objects;
import java.util.UUID;

/**
 * What an event is about: the record's kind and id, plus the inspection and project it hangs
 * off, denormalised so one query returns the whole timeline of an inspection including its
 * items, defects, NCRs and reinspections.
 *
 * @param type         the kind of record
 * @param id           the record's id
 * @param inspectionId the inspection the record belongs to, or is; null only for a subject
 *                     with no inspection at all
 * @param projectId    the project, when known
 */
public record InspectionEventSubject(InspectionEventSubjectType type,
                                     UUID id,
                                     UUID inspectionId,
                                     Long projectId) {

    public InspectionEventSubject {
        Objects.requireNonNull(type, "subject type");
        Objects.requireNonNull(id, "subject id");
    }

    public static InspectionEventSubject inspection(Inspection inspection) {
        return new InspectionEventSubject(InspectionEventSubjectType.INSPECTION,
                inspection.getId(), inspection.getId(), inspection.getProjectId());
    }

    public static InspectionEventSubject checkItem(InspectionCheckItem item) {
        Inspection inspection = item.getInspection();
        return new InspectionEventSubject(InspectionEventSubjectType.CHECK_ITEM,
                item.getId(), inspection.getId(), inspection.getProjectId());
    }

    public static InspectionEventSubject defect(InspectionDefect defect) {
        Inspection inspection = defect.getInspection();
        return new InspectionEventSubject(InspectionEventSubjectType.DEFECT,
                defect.getId(), inspection.getId(), inspection.getProjectId());
    }

    /** An NCR carries no project of its own; the caller resolves it from the inspection. */
    public static InspectionEventSubject ncr(Ncr ncr, Long projectId) {
        return new InspectionEventSubject(InspectionEventSubjectType.NCR,
                ncr.getId(), ncr.getInspectionId(), projectId);
    }

    public static InspectionEventSubject reinspection(UUID reinspectionId, UUID originalInspectionId,
                                                      Long projectId) {
        return new InspectionEventSubject(InspectionEventSubjectType.REINSPECTION,
                reinspectionId, originalInspectionId, projectId);
    }
}
