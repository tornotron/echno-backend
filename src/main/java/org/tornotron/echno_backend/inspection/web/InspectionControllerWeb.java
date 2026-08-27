package org.tornotron.echno_backend.inspection.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.inspection.InspectionCategory;
import org.tornotron.echno_backend.inspection.InspectionResult;
import org.tornotron.echno_backend.inspection.InspectionStatus;
import org.tornotron.echno_backend.inspection.InspectionTrade;
import org.tornotron.echno_backend.inspection.InspectionType;
import org.tornotron.echno_backend.inspection.dtos.CreateInspectionRequest;
import org.tornotron.echno_backend.inspection.dtos.InspectionDto;
import org.tornotron.echno_backend.inspection.dtos.UpdateInspectionRequest;
import org.tornotron.echno_backend.inspection.service.InspectionService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inspections/web")
@RequiredArgsConstructor
@Tag(
        name = "Inspections",
        description = "Site inspections carried out against a project, covering routine, safety and "
                + "compliance types. An inspection tracks its checklist items, defects, result and status. "
                + "All endpoints are tenant scoped and limited to system administrators and project "
                + "managers."
)
public class InspectionControllerWeb {

    private final InspectionService service;

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Create an inspection",
            description = "Creates a new inspection for a project, including its checklist items."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Inspection created"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No project with the given id in the current tenant")
    })
    public ResponseEntity<InspectionDto> create(@Valid @RequestBody CreateInspectionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Get an inspection by id",
            description = "Returns a single inspection including its checklist items and any recorded "
                    + "defects."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Inspection found"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No inspection with the given id in the current tenant")
    })
    public InspectionDto get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "List inspections",
            description = "Returns a page of inspections in the current tenant. The projectId, status, "
                    + "type, category, trade and result parameters are optional filters; omitting all of "
                    + "them returns every inspection, subject to paging."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of matching inspections"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public Page<InspectionDto> list(@RequestParam(required = false) Long projectId,
                                    @RequestParam(required = false) InspectionStatus status,
                                    @RequestParam(required = false) InspectionType type,
                                    @RequestParam(required = false) InspectionCategory category,
                                    @RequestParam(required = false) InspectionTrade trade,
                                    @RequestParam(required = false) InspectionResult result,
                                    Pageable pageable) {
        return service.findAll(projectId, status, type, category, trade, result, pageable);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Update an inspection",
            description = "Updates the status, result, checklist items and defects of an existing "
                    + "inspection identified by id."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Inspection updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No inspection with the given id in the current tenant")
    })
    public InspectionDto update(@PathVariable UUID id,
                                @Valid @RequestBody UpdateInspectionRequest req) {
        return service.update(id, req);
    }
}
