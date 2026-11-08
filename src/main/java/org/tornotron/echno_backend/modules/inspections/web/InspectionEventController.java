package org.tornotron.echno_backend.modules.inspections.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.tornotron.echno_backend.modules.inspections.service.DetectionVisibility;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The inspection event log on the tenant API paths the mobile client calls (#998).
 *
 * <p>{@link InspectionEventControllerWeb} writes {@code /web} into each handler's own path, so a
 * subclass cannot move them by setting a class-level path the way the other inspection
 * controllers do. Each handler is overridden here with the un-suffixed path, the same guard and a
 * call to the web handler, so the behaviour stays in one place. The guards are pinned equal to
 * the web ones by {@code MobilePathGuardParityTest}.
 */
@RestController
@RequireSubscription(feature = InspectionsModule.FEATURE_KEY)
@Tag(
        name = "Inspection history (Mobile)",
        description = "The tenant API path of the inspection event log, for the mobile client. Same "
                + "handlers, response shapes and organization-role guards as the web endpoints; send "
                + "X-Organization-Id to pick the organization."
)
public class InspectionEventController extends InspectionEventControllerWeb {

    public InspectionEventController(InspectionEventService service, DetectionVisibility detectionVisibility) {
        super(service, detectionVisibility);
    }

    @Override
    @GetMapping("/api/v1/inspections/{id}/events")
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(summary = "Read an inspection's timeline",
            description = "Same as GET /api/v1/inspections/web/{id}/events.")
    public Page<InspectionEventDto> inspectionTimeline(@PathVariable UUID id, Pageable pageable) {
        return super.inspectionTimeline(id, pageable);
    }

    @Override
    @GetMapping("/api/v1/ncrs/{id}/events")
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(summary = "Read a non-conformance report's timeline",
            description = "Same as GET /api/v1/ncrs/web/{id}/events.")
    public Page<InspectionEventDto> ncrTimeline(@PathVariable UUID id, Pageable pageable) {
        return super.ncrTimeline(id, pageable);
    }

    @Override
    @GetMapping("/api/v1/inspections/events")
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(summary = "Search the inspection event log",
            description = "Same as GET /api/v1/inspections/web/events.")
    public Page<InspectionEventDto> search(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) InspectionEventSubjectType subjectType,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            Pageable pageable) {
        return super.search(projectId, subjectType, eventType, actorId, from, to, pageable);
    }
}
