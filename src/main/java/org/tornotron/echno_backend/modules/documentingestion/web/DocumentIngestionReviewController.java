package org.tornotron.echno_backend.modules.documentingestion.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
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
import org.tornotron.echno_backend.modules.documentingestion.apply.ApplyService;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus;
import org.tornotron.echno_backend.modules.documentingestion.dto.ApplyResultDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.CandidateDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.DecideCandidatesRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.DecisionOutcomeDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.PackageDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.ProvenanceDto;
import org.tornotron.echno_backend.modules.documentingestion.gate.DocumentIngestionGate;
import org.tornotron.echno_backend.modules.documentingestion.review.ProvenanceService;
import org.tornotron.echno_backend.modules.documentingestion.review.ReviewService;
import org.tornotron.echno_backend.modules.documentingestion.service.IngestPackageService;

/** Review of proposed values, the apply step that writes accepted ones, and provenance by target. */
@RestController
@RequestMapping("/api/v1/document-ingestion")
@DocumentIngestionGate
@RequireSubscription(feature = DocumentIngestionModule.FEATURE_KEY)
@RequiredArgsConstructor
@Tag(name = "Document Ingestion")
public class DocumentIngestionReviewController {

    private final ReviewService reviewService;
    private final ApplyService applyService;
    private final ProvenanceService provenanceService;
    private final IngestPackageService packageService;

    @GetMapping("/packages/{packageId}/candidates")
    @PreAuthorize(DocumentIngestionModule.READ_GUARD)
    @Operation(summary = "List a package's candidate values with their sources, filtered by kind, status and segment")
    public Page<CandidateDto> listCandidates(@PathVariable UUID packageId,
                                             @RequestParam(required = false) CandidateKind kind,
                                             @RequestParam(required = false) CandidateStatus status,
                                             @RequestParam(required = false) UUID segmentId,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "50") int size) {
        return reviewService.list(packageId, kind, status, segmentId, page, size);
    }

    @PostMapping("/packages/{packageId}/candidates/decisions")
    @PreAuthorize(DocumentIngestionModule.REVIEW_GUARD)
    @Operation(summary = "Accept, edit or reject candidates, one or many",
            description = "Each row reports its own outcome. A candidate with no source is never accepted, and a "
                    + "flagged one is skipped unless every flag is acknowledged.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One outcome per decision"),
            @ApiResponse(responseCode = "403", description = "MODULE_DISABLED, or the caller cannot review"),
            @ApiResponse(responseCode = "404", description = "No such package in the current tenant")
    })
    public List<DecisionOutcomeDto> decide(@PathVariable UUID packageId,
                                           @Valid @RequestBody DecideCandidatesRequest req) {
        return reviewService.decide(packageId, req.decisions());
    }

    @PostMapping("/packages/{packageId}/apply")
    @PreAuthorize(DocumentIngestionModule.APPLY_GUARD)
    @Operation(summary = "Write a package's accepted values of one kind to their live records",
            description = "Also asks the guard of the target's own endpoint. Call again while remaining is above zero.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "What was applied, what failed and what remains"),
            @ApiResponse(responseCode = "400", description = "No applier for that kind yet"),
            @ApiResponse(responseCode = "403", description = "MODULE_DISABLED, or the caller cannot write the target")
    })
    public ApplyResultDto apply(@PathVariable UUID packageId, @RequestParam CandidateKind kind) {
        return applyService.apply(packageId, kind);
    }

    @GetMapping("/provenance")
    @PreAuthorize(DocumentIngestionModule.READ_GUARD)
    @Operation(summary = "Say where each field of a live record came from")
    public List<ProvenanceDto> provenance(@RequestParam String targetType, @RequestParam String targetId) {
        return provenanceService.forTarget(targetType, targetId);
    }

    @PostMapping("/packages/{packageId}/archive")
    @PreAuthorize(DocumentIngestionModule.UPLOAD_GUARD)
    @Operation(summary = "Archive a package. Packages are never deleted, so provenance keeps its sources")
    public PackageDto archive(@PathVariable UUID packageId) {
        return packageService.archive(packageId);
    }
}
