package org.tornotron.echno_backend.modules.documentingestion.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionModule;
import org.tornotron.echno_backend.modules.documentingestion.dto.BoqMappingRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.BoqSheetDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.BoqTargetRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.PackageDto;
import org.tornotron.echno_backend.modules.documentingestion.gate.DocumentIngestionGate;
import org.tornotron.echno_backend.modules.documentingestion.service.BoqSheetService;
import org.tornotron.echno_backend.modules.documentingestion.service.IngestPackageService;

/**
 * BOQ reading from a parsed workbook: find each sheet's header, read a sheet under the column
 * mapping a person confirms, and choose the sub-contract the accepted rows are written to. Review and
 * apply are the generic endpoints, with {@code kind=BOQ_ITEM}.
 */
@RestController
@RequestMapping("/api/v1/document-ingestion")
@DocumentIngestionGate
@RequireSubscription(feature = DocumentIngestionModule.FEATURE_KEY)
@RequiredArgsConstructor
@Tag(name = "Document Ingestion")
public class DocumentIngestionBoqController {

    private final BoqSheetService sheetService;
    private final IngestPackageService packageService;

    @PostMapping("/documents/{documentId}/boq/sheets/detect")
    @PreAuthorize(DocumentIngestionModule.UPLOAD_GUARD)
    @Operation(summary = "Find the header of each sheet of a parsed workbook and propose a column mapping",
            description = "Sheets already found keep their mapping. Reads no rows into candidates.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every sheet of the workbook, in workbook order"),
            @ApiResponse(responseCode = "400", description = "Not a workbook, not read yet, or the package is archived"),
            @ApiResponse(responseCode = "403", description = "MODULE_DISABLED, or the caller lacks an upload role"),
            @ApiResponse(responseCode = "404", description = "No such document in the current tenant")
    })
    public List<BoqSheetDto> detectSheets(@PathVariable UUID documentId) {
        return sheetService.detect(documentId);
    }

    @GetMapping("/documents/{documentId}/boq/sheets")
    @PreAuthorize(DocumentIngestionModule.READ_GUARD)
    @Operation(summary = "List a workbook's sheets with their mapping and what reading each found")
    public List<BoqSheetDto> listSheets(@PathVariable UUID documentId) {
        return sheetService.list(documentId);
    }

    @PostMapping("/documents/{documentId}/boq/sheets/{sheetId}/extract")
    @PreAuthorize(DocumentIngestionModule.UPLOAD_GUARD)
    @Operation(summary = "Confirm a sheet's column mapping and read its rows into BOQ candidates",
            description = "Reading a sheet again replaces its unapplied candidates. Refused once a row of the sheet is on a contract.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The sheet with what reading it found"),
            @ApiResponse(responseCode = "400", description = "A bad mapping, or rows of the sheet are already applied"),
            @ApiResponse(responseCode = "403", description = "MODULE_DISABLED, or the caller lacks an upload role"),
            @ApiResponse(responseCode = "404", description = "No such document or sheet in the current tenant")
    })
    public BoqSheetDto extractSheet(@PathVariable UUID documentId, @PathVariable UUID sheetId,
                                    @Valid @RequestBody BoqMappingRequest req) {
        return sheetService.extract(documentId, sheetId, req);
    }

    @PutMapping("/packages/{packageId}/boq-target")
    @PreAuthorize(DocumentIngestionModule.APPLY_GUARD)
    @Operation(summary = "Choose the sub-contract a package's BOQ is written to, or clear it",
            description = "With none chosen, accepted BOQ rows stay on the package as its tender BOQ.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The package with its target"),
            @ApiResponse(responseCode = "400", description = "The contract is in another project, or BOQ rows are already applied elsewhere"),
            @ApiResponse(responseCode = "403", description = "MODULE_DISABLED, or the caller cannot apply"),
            @ApiResponse(responseCode = "404", description = "No such package or sub-contract in the current tenant")
    })
    public PackageDto setBoqTarget(@PathVariable UUID packageId, @Valid @RequestBody BoqTargetRequest req) {
        return packageService.setBoqTarget(packageId, req);
    }
}
