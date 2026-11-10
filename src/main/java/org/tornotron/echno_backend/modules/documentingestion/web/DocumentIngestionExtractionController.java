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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionModule;
import org.tornotron.echno_backend.modules.documentingestion.dto.ClassificationRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.ConfirmAllResultDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.DocumentClassificationDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.ExtractJobDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.IngestSettingsDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.LlmBudgetRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.LlmCallDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.LlmSpendDto;
import org.tornotron.echno_backend.modules.documentingestion.gate.DocumentIngestionGate;
import org.tornotron.echno_backend.modules.documentingestion.service.ClassificationService;
import org.tornotron.echno_backend.modules.documentingestion.service.ExtractionService;
import org.tornotron.echno_backend.modules.documentingestion.service.IngestSettingsService;

/**
 * Class confirmation, the extract queue, the ledger of what was sent to the outside AI service, and the
 * budget that limits it.
 */
@RestController
@RequestMapping("/api/v1/document-ingestion")
@DocumentIngestionGate
@RequireSubscription(feature = DocumentIngestionModule.FEATURE_KEY)
@RequiredArgsConstructor
@Tag(name = "Document Ingestion")
public class DocumentIngestionExtractionController {

    private final ClassificationService classificationService;
    private final ExtractionService extractionService;
    private final IngestSettingsService settingsService;

    @GetMapping("/packages/{packageId}/classification")
    @PreAuthorize("@access.can('document-ingestion', 'read')")
    @Operation(summary = "List a package's documents with their proposed or confirmed classes and page segments")
    public Page<DocumentClassificationDto> classification(@PathVariable UUID packageId,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "50") int size) {
        return classificationService.list(packageId, page, size);
    }

    @PutMapping("/documents/{documentId}/classification")
    @PreAuthorize("@access.can('document-ingestion', 'upload')")
    @Operation(summary = "Confirm a document's class and page segments",
            description = "Replaces what was proposed. Each confirmed segment queues the readers of its class.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The document as confirmed"),
            @ApiResponse(responseCode = "400", description = "Pages out of range or overlapping, or a segment already applied"),
            @ApiResponse(responseCode = "403", description = "MODULE_DISABLED, or the caller may not make this change to document ingestion")
    })
    public DocumentClassificationDto confirm(@PathVariable UUID documentId,
                                             @Valid @RequestBody ClassificationRequest req) {
        return classificationService.confirm(documentId, req);
    }

    @PostMapping("/packages/{packageId}/classification/confirm-all")
    @PreAuthorize("@access.can('document-ingestion', 'upload')")
    @Operation(summary = "Confirm every proposed class and segment of a package as proposed",
            description = "Call again while remaining is above zero.")
    public ConfirmAllResultDto confirmAll(@PathVariable UUID packageId) {
        return classificationService.confirmAll(packageId);
    }

    @GetMapping("/packages/{packageId}/extract-jobs")
    @PreAuthorize("@access.can('document-ingestion', 'read')")
    @Operation(summary = "List a package's extract jobs, with the pages each could not read")
    public Page<ExtractJobDto> extractJobs(@PathVariable UUID packageId,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        return extractionService.jobs(packageId, page, size);
    }

    @PostMapping("/extract-jobs/{jobId}/retry-failed")
    @PreAuthorize("@access.can('document-ingestion', 'upload')")
    @Operation(summary = "Read the failed parts of a job again",
            description = "Parts already read are skipped, so only the failed chunks are sent.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The job, queued again"),
            @ApiResponse(responseCode = "400", description = "The job did not end PARTIAL or FAILED")
    })
    public ExtractJobDto retryFailed(@PathVariable UUID jobId) {
        return extractionService.retryFailed(jobId);
    }

    @GetMapping("/llm-calls")
    @PreAuthorize("@access.can('document-ingestion', 'admin')")
    @Operation(summary = "List the calls that sent pages to the outside AI service, newest first",
            description = "Which pages of which file left Echno, when, with which model and prompt version, and what "
                    + "they cost. Holds no document text.")
    public Page<LlmCallDto> llmCalls(@RequestParam(required = false) UUID packageId,
                                     @RequestParam(required = false) UUID documentId,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "50") int size) {
        return extractionService.calls(packageId, documentId, page, size);
    }

    @GetMapping("/packages/{packageId}/llm-calls")
    @PreAuthorize("@access.can('document-ingestion', 'read')")
    @Operation(summary = "List the calls that sent pages of this package to the outside AI service, newest first")
    public Page<LlmCallDto> packageLlmCalls(@PathVariable UUID packageId,
                                            @RequestParam(required = false) UUID documentId,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size) {
        return extractionService.calls(packageId, documentId, page, size);
    }

    @GetMapping("/packages/{packageId}/llm-spend")
    @PreAuthorize("@access.can('document-ingestion', 'read')")
    @Operation(summary = "Read what reading this package has used, against the package's and the month's limits")
    public LlmSpendDto spend(@PathVariable UUID packageId) {
        return extractionService.spend(packageId);
    }

    @PutMapping("/packages/{packageId}/llm-budget")
    @PreAuthorize("@access.can('document-ingestion', 'admin')")
    @Operation(summary = "Set a package's AI reading limit", description = "A job paused on the budget resumes once it has room.")
    public LlmSpendDto setPackageBudget(@PathVariable UUID packageId, @Valid @RequestBody LlmBudgetRequest req) {
        return extractionService.setPackageBudget(packageId, req);
    }

    @PutMapping("/settings/llm-budget")
    @PreAuthorize("@access.can('document-ingestion', 'admin')")
    @Operation(summary = "Set the organization's monthly AI reading limit",
            description = "Each change is recorded. A job paused on the budget resumes once it has room.")
    public IngestSettingsDto setMonthlyBudget(@Valid @RequestBody LlmBudgetRequest req) {
        return settingsService.setMonthlyBudget(req.budgetUsd(), req.budgetTokens());
    }
}
