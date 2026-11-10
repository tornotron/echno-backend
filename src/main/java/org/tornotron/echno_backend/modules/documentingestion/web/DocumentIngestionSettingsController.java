package org.tornotron.echno_backend.modules.documentingestion.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionModule;
import org.tornotron.echno_backend.modules.documentingestion.dto.IngestSettingsDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.LlmReadingRequest;
import org.tornotron.echno_backend.modules.documentingestion.gate.DocumentIngestionGate;
import org.tornotron.echno_backend.modules.documentingestion.service.IngestSettingsService;

/** The organization's document ingestion settings: today, whether tender text may go to the outside AI service. */
@RestController
@RequestMapping("/api/v1/document-ingestion")
@DocumentIngestionGate
@RequireSubscription(feature = DocumentIngestionModule.FEATURE_KEY)
@RequiredArgsConstructor
@Tag(name = "Document Ingestion")
public class DocumentIngestionSettingsController {

    private final IngestSettingsService settingsService;

    @GetMapping("/settings")
    @PreAuthorize("@access.can('document-ingestion', 'read')")
    @Operation(summary = "Read the organization's settings, with the last changes and who made them")
    public IngestSettingsDto get() {
        return settingsService.get();
    }

    @PutMapping("/settings/llm-reading")
    @PreAuthorize("@access.can('document-ingestion', 'admin')")
    @Operation(summary = "Turn reading tender text with the outside AI service on or off",
            description = "On by default. Each change is recorded with who made it and when.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The settings after the change"),
            @ApiResponse(responseCode = "403", description = "MODULE_DISABLED, or the caller may not make this change to document ingestion")
    })
    public IngestSettingsDto setLlmReading(@Valid @RequestBody LlmReadingRequest req) {
        return settingsService.setLlmReading(req.enabled());
    }
}
