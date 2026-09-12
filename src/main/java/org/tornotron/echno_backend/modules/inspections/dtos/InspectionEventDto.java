package org.tornotron.echno_backend.modules.inspections.dtos;

import org.tornotron.echno_backend.modules.inspections.events.InspectionEventActorType;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventSubjectType;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/** One timeline entry as the web app reads it. */
public record InspectionEventDto(UUID id,
                                 InspectionEventSubjectType subjectType,
                                 UUID subjectId,
                                 UUID inspectionId,
                                 Long projectId,
                                 String eventType,
                                 InspectionEventActorType actorType,
                                 String actorId,
                                 LocalDateTime occurredAt,
                                 Map<String, Object> before,
                                 Map<String, Object> after,
                                 String note,
                                 String requestId) {
}
