package org.tornotron.echno_backend.modules.inspections.repositories;

import org.tornotron.echno_backend.modules.inspections.events.InspectionEventSubjectType;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.UUID;

/**
 * The narrowing a timeline read applies. Every field is optional and they narrow together;
 * {@code subjectIds} is an OR over subject ids and is what lets an NCR's timeline include the
 * reinspections raised on it.
 */
public record InspectionEventFilter(UUID inspectionId,
                                    InspectionEventSubjectType subjectType,
                                    Collection<UUID> subjectIds,
                                    Long projectId,
                                    String eventType,
                                    String actorId,
                                    LocalDateTime from,
                                    LocalDateTime to) {

    public static InspectionEventFilter forInspection(UUID inspectionId) {
        return new InspectionEventFilter(inspectionId, null, null, null, null, null, null, null);
    }
}
