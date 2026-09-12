package org.tornotron.echno_backend.modules.inspections.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.modules.inspections.InspectionsModule;
import org.tornotron.echno_backend.modules.inspections.dtos.InspectionEventDto;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventService;
import org.tornotron.echno_backend.modules.inspections.events.InspectionEventSubjectType;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Reads of the inspection event log. Nothing writes events through the API: they are recorded
 * by the services in the same transaction as the change they describe.
 */
@RestController
@RequireSubscription(feature = InspectionsModule.FEATURE_KEY)
@RequiredArgsConstructor
@Tag(
        name = "Inspection history",
        description = "The append-only audit log of the inspection module: who did what, to which "
                + "inspection, check point, defect, non-conformance report or reinspection, when, "
                + "and the before and after of each change. Every timeline is oldest first and "
                + "tenant scoped. Events are written by the server as the change happens and "
                + "cannot be edited or removed."
)
public class InspectionEventControllerWeb {

    private final InspectionEventService service;

    @GetMapping("/api/v1/inspections/web/{id}/events")
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(
            summary = "Read an inspection's timeline",
            description = "Every event on the inspection and on its check points, defects, "
                    + "non-conformance reports and reinspections, oldest first."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of events"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No inspection with the given id in the current tenant")
    })
    public Page<InspectionEventDto> inspectionTimeline(@PathVariable UUID id, Pageable pageable) {
        return service.inspectionTimeline(id, pageable);
    }

    @GetMapping("/api/v1/ncrs/web/{id}/events")
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(
            summary = "Read a non-conformance report's timeline",
            description = "Every event on the NCR and on the reinspections raised against it, "
                    + "oldest first."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of events"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No NCR with the given id in the current tenant")
    })
    public Page<InspectionEventDto> ncrTimeline(@PathVariable UUID id, Pageable pageable) {
        return service.ncrTimeline(id, pageable);
    }

    @GetMapping("/api/v1/inspections/web/events")
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(
            summary = "Search the inspection event log",
            description = "The project-wide log. Every parameter is an optional filter and they "
                    + "narrow together; from and to bound occurredAt inclusively."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of matching events"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public Page<InspectionEventDto> search(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) InspectionEventSubjectType subjectType,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            Pageable pageable) {
        return service.search(projectId, subjectType, eventType, actorId, from, to, pageable);
    }
}
