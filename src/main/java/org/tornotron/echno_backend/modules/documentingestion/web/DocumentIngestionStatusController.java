package org.tornotron.echno_backend.modules.documentingestion.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionModule;
import org.tornotron.echno_backend.modules.documentingestion.dto.DocumentIngestionStatusDto;
import org.tornotron.echno_backend.modules.documentingestion.gate.DocumentIngestionGate;

/** Says the module is open to the caller's organization. Answers 403 {@code MODULE_DISABLED} when it is not. */
@RestController
@RequestMapping("/api/v1/document-ingestion")
@DocumentIngestionGate
@RequireSubscription(feature = DocumentIngestionModule.FEATURE_KEY)
@Tag(name = "Document Ingestion",
        description = "Tender documents read into candidate values that a person accepts, each linked to its "
                + "source. Off by default: every endpoint answers 403 MODULE_DISABLED until an operator opens "
                + "the module for the organization.")
public class DocumentIngestionStatusController {

    @GetMapping("/status")
    @PreAuthorize(DocumentIngestionModule.READ_GUARD)
    @Operation(summary = "Say whether the module is open to the caller's organization")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The module is open"),
            @ApiResponse(responseCode = "403", description = "MODULE_DISABLED, or the caller is not a member of the current tenant")
    })
    public DocumentIngestionStatusDto status() {
        return new DocumentIngestionStatusDto(DocumentIngestionModule.ID, DocumentIngestionModule.VERSION);
    }
}
