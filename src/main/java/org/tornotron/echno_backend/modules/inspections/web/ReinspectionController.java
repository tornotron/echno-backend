package org.tornotron.echno_backend.modules.inspections.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.modules.inspections.InspectionsModule;
import org.tornotron.echno_backend.modules.inspections.dtos.ReinspectionDto;
import org.tornotron.echno_backend.modules.inspections.dtos.ReinspectionOutcomeRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.ScheduleReinspectionRequest;
import org.tornotron.echno_backend.modules.inspections.service.ReinspectionService;

import java.util.List;
import java.util.UUID;

/**
 * Reinspections on the tenant API paths the mobile client calls (#998).
 *
 * <p>{@link ReinspectionControllerWeb} writes {@code /web} into each handler's own path, so each
 * handler is overridden here with the un-suffixed path, the same guard and a call to the web
 * handler. The guards are pinned equal to the web ones by {@code MobilePathGuardParityTest}.
 */
@RestController
@RequireSubscription(feature = InspectionsModule.FEATURE_KEY)
@Tag(
        name = "Reinspections (Mobile)",
        description = "The tenant API path of the reinspection endpoints, for the mobile client. Same "
                + "handlers, request and response shapes and organization-role guards as the web "
                + "endpoints; send X-Organization-Id to pick the organization."
)
public class ReinspectionController extends ReinspectionControllerWeb {

    public ReinspectionController(ReinspectionService service) {
        super(service);
    }

    @Override
    @PostMapping("/api/v1/ncrs/{ncrId}/reinspections")
    @PreAuthorize("@inspectionSecurity.maySignOffNcr(#ncrId, @access.can('ncrs', 'sign-off-quality'), @access.can('ncrs', 'sign-off-safety'))")
    @Operation(summary = "Schedule a reinspection of an NCR",
            description = "Same as POST /api/v1/ncrs/web/{ncrId}/reinspections.")
    public ResponseEntity<ReinspectionDto> scheduleForNcr(@PathVariable UUID ncrId,
                                                          @Valid @RequestBody(required = false) ScheduleReinspectionRequest req) {
        return super.scheduleForNcr(ncrId, req);
    }

    @Override
    @GetMapping("/api/v1/ncrs/{ncrId}/reinspections")
    @PreAuthorize("@access.can('ncrs', 'read')")
    @Operation(summary = "List the reinspection attempts on an NCR",
            description = "Same as GET /api/v1/ncrs/web/{ncrId}/reinspections.")
    public List<ReinspectionDto> listForNcr(@PathVariable UUID ncrId) {
        return super.listForNcr(ncrId);
    }

    @Override
    @PostMapping("/api/v1/inspections/defects/{defectId}/reinspections")
    @PreAuthorize("@access.can('inspections', 'create')")
    @Operation(summary = "Schedule a reinspection of a defect",
            description = "Same as POST /api/v1/inspections/web/defects/{defectId}/reinspections.")
    public ResponseEntity<ReinspectionDto> scheduleForDefect(@PathVariable UUID defectId,
                                                             @Valid @RequestBody(required = false) ScheduleReinspectionRequest req) {
        return super.scheduleForDefect(defectId, req);
    }

    @Override
    @GetMapping("/api/v1/inspections/reinspections/{id}")
    @PreAuthorize("@access.can('inspections', 'read')")
    @Operation(summary = "Get a reinspection by id",
            description = "Same as GET /api/v1/inspections/web/reinspections/{id}.")
    public ReinspectionDto get(@PathVariable UUID id) {
        return super.get(id);
    }

    @Override
    @PostMapping("/api/v1/inspections/reinspections/{id}/outcome")
    @PreAuthorize("@inspectionSecurity.mayRecordReinspectionOutcome(#id, @access.can('ncrs', 'sign-off-quality'), @access.can('ncrs', 'sign-off-safety'))")
    @Operation(summary = "Record what the reinspection found",
            description = "Same as POST /api/v1/inspections/web/reinspections/{id}/outcome.")
    public ReinspectionDto recordOutcome(@PathVariable UUID id,
                                         @Valid @RequestBody ReinspectionOutcomeRequest req) {
        return super.recordOutcome(id, req);
    }
}
