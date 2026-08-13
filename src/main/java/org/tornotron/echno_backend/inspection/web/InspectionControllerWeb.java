package org.tornotron.echno_backend.inspection.web;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.inspection.InspectionResult;
import org.tornotron.echno_backend.inspection.InspectionStatus;
import org.tornotron.echno_backend.inspection.InspectionType;
import org.tornotron.echno_backend.inspection.dtos.CreateInspectionRequest;
import org.tornotron.echno_backend.inspection.dtos.InspectionDto;
import org.tornotron.echno_backend.inspection.dtos.UpdateInspectionRequest;
import org.tornotron.echno_backend.inspection.service.InspectionService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inspections/web")
@RequiredArgsConstructor
public class InspectionControllerWeb {

    private final InspectionService service;

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public ResponseEntity<InspectionDto> create(@Valid @RequestBody CreateInspectionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public InspectionDto get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public Page<InspectionDto> list(@RequestParam(required = false) Long projectId,
                                    @RequestParam(required = false) InspectionStatus status,
                                    @RequestParam(required = false) InspectionType type,
                                    @RequestParam(required = false) InspectionResult result,
                                    Pageable pageable) {
        return service.findAll(projectId, status, type, result, pageable);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public InspectionDto update(@PathVariable UUID id,
                                @Valid @RequestBody UpdateInspectionRequest req) {
        return service.update(id, req);
    }
}
