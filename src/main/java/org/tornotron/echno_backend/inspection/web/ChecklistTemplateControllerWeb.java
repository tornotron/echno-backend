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
import org.tornotron.echno_backend.inspection.InspectionTrade;
import org.tornotron.echno_backend.inspection.dtos.ChecklistTemplateDto;
import org.tornotron.echno_backend.inspection.dtos.ChecklistTemplateRequest;
import org.tornotron.echno_backend.inspection.dtos.StarterChecklistTemplateDto;
import org.tornotron.echno_backend.inspection.service.ChecklistTemplateService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/checklist-templates/web")
@RequiredArgsConstructor
@Tag(
        name = "Checklist templates",
        description = "The reusable per-trade checklists an organization inspects against. An "
                + "inspection created for a trade starts from a copy of that trade's active "
                + "template, so later edits never rewrite a checklist already signed off. The "
                + "starter endpoints expose the product-supplied defaults an organization adopts "
                + "as its own editable templates. All endpoints are tenant scoped. Defining a "
                + "template is the QA engineer's authority: setting the tolerance work is judged "
                + "against is a bigger act than recording a measurement against one. Everyone with "
                + "inspection read access can see the templates."
)
public class ChecklistTemplateControllerWeb {

    private final ChecklistTemplateService service;

    @PostMapping
    @PreAuthorize("@inspectionSecurity.canDefineChecklists()")
    @Operation(
            summary = "Define a checklist template",
            description = "Creates the organization's checklist for a trade, with its check points. "
                    + "There is one template per trade per organization."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Template created"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "409", description = "The tenant already has a template for that trade")
    })
    public ResponseEntity<ChecklistTemplateDto> create(@Valid @RequestBody ChecklistTemplateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(
            summary = "Get a checklist template by id",
            description = "Returns a single template including its check points in line order."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Template found"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No template with the given id in the current tenant")
    })
    public ChecklistTemplateDto get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @GetMapping
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(
            summary = "List checklist templates",
            description = "Returns a page of the organization's templates. The trade and active "
                    + "parameters are optional filters; omitting both returns every template, "
                    + "subject to paging."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of matching templates"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public Page<ChecklistTemplateDto> list(@RequestParam(required = false) InspectionTrade trade,
                                           @RequestParam(required = false) Boolean active,
                                           Pageable pageable) {
        return service.findAll(trade, active, pageable);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@inspectionSecurity.canDefineChecklists()")
    @Operation(
            summary = "Replace a checklist template",
            description = "Replaces the name, description, active flag and check points of an "
                    + "existing template and bumps its version. The trade is fixed when the "
                    + "template is created and cannot be changed here. Inspections already created "
                    + "from this template are unaffected: they hold their own copy of the criteria."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Template updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body, or the "
                    + "payload names a different trade"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No template with the given id in the current tenant")
    })
    public ChecklistTemplateDto update(@PathVariable UUID id,
                                       @Valid @RequestBody ChecklistTemplateRequest req) {
        return service.update(id, req);
    }

    @GetMapping("/starters")
    @PreAuthorize("@inspectionSecurity.canRead()")
    @Operation(
            summary = "List the starter checklists on offer",
            description = "Returns the product-supplied default checklists, one per trade at most. "
                    + "These are global reference data and identical for every organization; adopt "
                    + "one to get an editable copy of your own."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The available starter checklists"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public List<StarterChecklistTemplateDto> listStarters() {
        return service.findStarters();
    }

    @PostMapping("/starters/{trade}/adopt")
    @PreAuthorize("@inspectionSecurity.canDefineChecklists()")
    @Operation(
            summary = "Adopt a starter checklist",
            description = "Copies the shipped starter for a trade into this organization as its own "
                    + "editable template. The copy is a snapshot: later revisions of the starter do "
                    + "not reach into it."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Template created from the starter"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No starter checklist is available for that trade"),
            @ApiResponse(responseCode = "409", description = "The tenant already has a template for that trade")
    })
    public ResponseEntity<ChecklistTemplateDto> adoptStarter(@PathVariable InspectionTrade trade) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.adoptStarter(trade));
    }
}
