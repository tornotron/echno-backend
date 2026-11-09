package org.tornotron.echno_backend.modules.documentingestion.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionModule;
import org.tornotron.echno_backend.modules.documentingestion.dto.CompanyEvidenceDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.CompanyProfileDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.CompanyProfileRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.ComplianceMatrixDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.DownloadUrlDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.EvidenceUploadDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.PresignEvidenceRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.QualificationRequirementEntryDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.RegisterDocumentRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.RequirementStatusRequest;
import org.tornotron.echno_backend.modules.documentingestion.gate.DocumentIngestionGate;
import org.tornotron.echno_backend.modules.documentingestion.service.QualificationService;

/**
 * Qualification and bidding (design section 4.7): the company profile and its evidence files, a
 * package's qualification requirements, and the Tender Compliance Matrix. The matrix shows matches and
 * gaps and makes no bid or no-bid recommendation.
 */
@RestController
@RequestMapping("/api/v1/document-ingestion")
@DocumentIngestionGate
@RequireSubscription(feature = DocumentIngestionModule.FEATURE_KEY)
@RequiredArgsConstructor
@Tag(name = "Document Ingestion")
public class DocumentIngestionQualificationController {

    private final QualificationService qualification;

    @GetMapping("/company-profile")
    @PreAuthorize(DocumentIngestionModule.READ_GUARD)
    @Operation(summary = "Get the company profile for bidding",
            description = "Turnover by financial year, similar projects with their values, years in business, the "
                    + "equipment list, the personnel list and the uploaded evidence files. Empty lists and a null id "
                    + "until an admin first saves it.")
    public CompanyProfileDto getCompanyProfile() {
        return qualification.profile();
    }

    @PutMapping("/company-profile")
    @PreAuthorize(DocumentIngestionModule.ADMIN_GUARD)
    @Operation(summary = "Save the company profile",
            description = "Replaces every field with what is sent; evidence files are kept. Typed in by hand, never "
                    + "read from documents. A field left empty shows as UNKNOWN in the compliance matrix.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The saved profile"),
            @ApiResponse(responseCode = "400", description = "A field is out of range, or a financial year is given twice")
    })
    public CompanyProfileDto saveCompanyProfile(@Valid @RequestBody CompanyProfileRequest req) {
        return qualification.saveProfile(req);
    }

    @PostMapping("/company-profile/evidence/presign")
    @PreAuthorize(DocumentIngestionModule.ADMIN_GUARD)
    @Operation(summary = "Declare an evidence file and get a URL to PUT it to",
            description = "For a turnover certificate, a completion certificate, an equipment register or a staff "
                    + "list. The file goes straight to storage; register it once the upload is done.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The evidence file and its upload URL"),
            @ApiResponse(responseCode = "400", description = "Type, size or count limit refused")
    })
    public EvidenceUploadDto presignEvidence(@Valid @RequestBody PresignEvidenceRequest req) {
        return qualification.presignEvidence(req);
    }

    @PostMapping("/company-profile/evidence/{evidenceId}/register")
    @PreAuthorize(DocumentIngestionModule.ADMIN_GUARD)
    @Operation(summary = "Confirm an evidence upload")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Registered; shown beside the rows of its section"),
            @ApiResponse(responseCode = "400", description = "Wrong key, already registered, or nothing uploaded"),
            @ApiResponse(responseCode = "404", description = "No such evidence file in the current tenant")
    })
    public CompanyEvidenceDto registerEvidence(@PathVariable UUID evidenceId,
                                               @Valid @RequestBody RegisterDocumentRequest req) {
        return qualification.registerEvidence(evidenceId, req);
    }

    @DeleteMapping("/company-profile/evidence/{evidenceId}")
    @PreAuthorize(DocumentIngestionModule.ADMIN_GUARD)
    @Operation(summary = "Remove an evidence file")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Removed, with its stored file"),
            @ApiResponse(responseCode = "404", description = "No such evidence file in the current tenant")
    })
    public ResponseEntity<Void> deleteEvidence(@PathVariable UUID evidenceId) {
        qualification.deleteEvidence(evidenceId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/company-profile/evidence/{evidenceId}/download")
    @PreAuthorize(DocumentIngestionModule.READ_GUARD)
    @Operation(summary = "Get a short-lived link to an evidence file")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The link, valid for a few minutes"),
            @ApiResponse(responseCode = "400", description = "Not uploaded yet"),
            @ApiResponse(responseCode = "404", description = "No such evidence file in the current tenant")
    })
    public DownloadUrlDto evidenceDownload(@PathVariable UUID evidenceId) {
        return qualification.evidenceUrl(evidenceId);
    }

    @GetMapping("/packages/{packageId}/qualification-requirements")
    @PreAuthorize(DocumentIngestionModule.READ_GUARD)
    @Operation(summary = "List a package's qualification requirements",
            description = "Applied from accepted QUALIFICATION_REQUIREMENT candidates, as printed: turnover, similar "
                    + "projects, experience, equipment and personnel. Provenance for each field is under "
                    + "GET /provenance with target type QUALIFICATION_REQUIREMENT.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The requirements, in matrix order"),
            @ApiResponse(responseCode = "404", description = "No such package in the current tenant")
    })
    public List<QualificationRequirementEntryDto> listQualificationRequirements(@PathVariable UUID packageId) {
        return qualification.requirements(packageId);
    }

    @PutMapping("/qualification-requirements/{requirementId}/status")
    @PreAuthorize(DocumentIngestionModule.APPLY_GUARD)
    @Operation(summary = "Set a person's status on an equipment or personnel requirement",
            description = "Equipment and personnel are text, so a system-admin or project-manager compares them with "
                    + "the profile and sets MATCH, GAP or UNKNOWN; null clears it. Who set it and when is kept. Refused "
                    + "on turnover, similar projects and experience, which are compared from their figures.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The requirement with its status"),
            @ApiResponse(responseCode = "400", description = "Compared from figures, or the package is not at TENDER"),
            @ApiResponse(responseCode = "404", description = "No such requirement in the current tenant")
    })
    public QualificationRequirementEntryDto setRequirementStatus(@PathVariable UUID requirementId,
                                                                 @Valid @RequestBody RequirementStatusRequest req) {
        return qualification.setStatus(requirementId, req);
    }

    @GetMapping("/packages/{packageId}/compliance-matrix")
    @PreAuthorize(DocumentIngestionModule.READ_GUARD)
    @Operation(summary = "Get the Tender Compliance Matrix",
            description = "One row per qualification requirement: the tender's value, the company's value, a status "
                    + "(MATCH, GAP or UNKNOWN) and the evidence files of that profile section. Turnover, similar "
                    + "projects and experience are compared automatically; equipment and personnel carry the status a "
                    + "person set. A blank profile field gives UNKNOWN, never GAP. Hidden at stage AWARDED. No bid or "
                    + "no-bid recommendation is made.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The matrix, or shown=false at stage AWARDED"),
            @ApiResponse(responseCode = "404", description = "No such package in the current tenant")
    })
    public ComplianceMatrixDto complianceMatrix(@PathVariable UUID packageId) {
        return qualification.matrix(packageId);
    }
}
