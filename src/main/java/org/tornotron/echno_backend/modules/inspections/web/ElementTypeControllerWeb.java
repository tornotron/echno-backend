package org.tornotron.echno_backend.modules.inspections.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.modules.inspections.InspectionsModule;
import org.tornotron.echno_backend.modules.inspections.dtos.CreateElementTypeRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.ElementTypeCatalogueDto;
import org.tornotron.echno_backend.modules.inspections.dtos.OrgElementTypeDto;
import org.tornotron.echno_backend.modules.inspections.dtos.UpdateElementTypeRequest;
import org.tornotron.echno_backend.modules.inspections.service.ElementTypeService;

import java.util.List;
import java.util.UUID;

@RestController
@RequireSubscription(feature = InspectionsModule.FEATURE_KEY)
@RequestMapping("/api/v1/inspections/web/element-types")
@RequiredArgsConstructor
@Tag(
        name = "Element types",
        description = "The construction element types an organization inspects: the seeded "
                + "catalogue copied into the tenant on first use, plus any the organization defines. "
                + "A spatial node of level ELEMENT carries one of these codes, and a checklist "
                + "template's applicability names the ones it suits. Managing the list is the same "
                + "authority as defining checklists. Types are deactivated, never deleted."
)
public class ElementTypeControllerWeb {

    private final ElementTypeService service;

    @GetMapping
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(summary = "List the organization's element types",
            description = "Active types by default, in sort order then name. Pass includeInactive=true "
                    + "to see retired ones too.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The organization's element types"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public List<OrgElementTypeDto> list(@RequestParam(required = false, defaultValue = "false") boolean includeInactive) {
        return service.listOrgElementTypes(includeInactive);
    }

    @GetMapping("/catalogue")
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(summary = "List the shipped element type catalogue",
            description = "The global catalogue every organization's list is copied from. Read only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The catalogue"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public List<ElementTypeCatalogueDto> catalogue() {
        return service.listCatalogue();
    }

    @PostMapping
    @PreAuthorize("@inspectionSecurity.canDefineChecklists()")
    @Operation(summary = "Define an element type",
            description = "Adds an organization-defined element type. It appears in pickers at once.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Element type created"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "409", description = "The organization already has an element type with that code")
    })
    public ResponseEntity<OrgElementTypeDto> create(@Valid @RequestBody CreateElementTypeRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("@inspectionSecurity.canDefineChecklists()")
    @Operation(summary = "Rename, regroup, reorder or deactivate an element type",
            description = "The code is immutable, including on catalogue copies.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Element type updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No element type with the given id in the current tenant")
    })
    public OrgElementTypeDto update(@PathVariable UUID id, @Valid @RequestBody UpdateElementTypeRequest req) {
        return service.update(id, req);
    }
}
