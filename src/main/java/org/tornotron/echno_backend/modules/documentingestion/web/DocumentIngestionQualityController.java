package org.tornotron.echno_backend.modules.documentingestion.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionModule;
import org.tornotron.echno_backend.modules.documentingestion.apply.ChecklistCopier;
import org.tornotron.echno_backend.modules.documentingestion.dto.CopyToTemplateRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.CopyToTemplateResultDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.QualityRequirementEntryDto;
import org.tornotron.echno_backend.modules.documentingestion.gate.DocumentIngestionGate;
import org.tornotron.echno_backend.modules.documentingestion.service.QualityRegisterService;
import org.tornotron.echno_backend.modules.inspections.api.ChecklistTemplatePort;

/**
 * The inspection and test plan read from a tender's specification (design section 4.4), by package and by
 * project, and the separate action that copies chosen rows into the organization's checklist templates.
 */
@RestController
@RequestMapping("/api/v1/document-ingestion")
@DocumentIngestionGate
@RequireSubscription(feature = DocumentIngestionModule.FEATURE_KEY)
@RequiredArgsConstructor
@Tag(name = "Document Ingestion")
public class DocumentIngestionQualityController {

    private final QualityRegisterService registers;
    private final ChecklistCopier copier;

    @GetMapping("/packages/{packageId}/quality-requirements")
    @PreAuthorize("@access.can('document-ingestion', 'read')")
    @Operation(summary = "List a package's inspection and test plan",
            description = "One row per quality requirement applied from the specification, each value as printed, with "
                    + "the page, box and quote behind it. A frequency or acceptance criterion the specification does not "
                    + "state is null and named in notStated; Echno does not fill one in. Written only by applying "
                    + "accepted QUALITY_REQUIREMENT candidates; provenance is under GET /provenance with target type "
                    + "QUALITY_REQUIREMENT.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of rows, by trade and activity"),
            @ApiResponse(responseCode = "404", description = "No such package in the current tenant")
    })
    public Page<QualityRequirementEntryDto> listForPackage(@PathVariable UUID packageId,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "50") int size) {
        return registers.forPackage(packageId, page, size);
    }

    @GetMapping("/projects/{projectId}/quality-requirements")
    @PreAuthorize("@access.can('document-ingestion', 'read')")
    @Operation(summary = "List a project's inspection and test plan",
            description = "The rows of every package on the project, with citations, as for the package list.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of rows, by trade and activity"),
            @ApiResponse(responseCode = "404", description = "No such project in the current tenant")
    })
    public Page<QualityRequirementEntryDto> listForProject(@PathVariable Long projectId,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "50") int size) {
        return registers.forProject(projectId, page, size);
    }

    @PostMapping("/quality-requirements/copy-to-template")
    @PreAuthorize(ChecklistTemplatePort.DEFINE_GUARD)
    @Operation(summary = "Copy rows of the inspection and test plan into checklist templates",
            description = "Adds each row as a check point at the end of the checklist template of its trade (or of "
                    + "tradeId), in a new revision, defining the template when the trade has none. Existing check "
                    + "points are kept. A row copied before is reported as ALREADY_COPIED, a row with no trade as "
                    + "NO_TRADE. Needs the role that defines checklists, and the inspections module on for the "
                    + "organization.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The templates changed and each row's outcome"),
            @ApiResponse(responseCode = "400", description = "Site inspections are off for the organization, or a "
                    + "value breaks a template limit"),
            @ApiResponse(responseCode = "403", description = "MODULE_DISABLED, or the caller cannot define checklists"),
            @ApiResponse(responseCode = "404", description = "The trade named is not one of the organization's")
    })
    public CopyToTemplateResultDto copyToTemplate(@Valid @RequestBody CopyToTemplateRequest req) {
        return copier.copy(req);
    }
}
