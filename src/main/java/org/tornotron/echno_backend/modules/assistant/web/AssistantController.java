package org.tornotron.echno_backend.modules.assistant.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.common.pagination.PageQuery20;
import org.tornotron.echno_backend.modules.assistant.AssistantModule;
import org.tornotron.echno_backend.modules.assistant.AssistantModuleEnabled;
import org.tornotron.echno_backend.modules.assistant.dto.AskRequest;
import org.tornotron.echno_backend.modules.assistant.dto.AskResponse;
import org.tornotron.echno_backend.modules.assistant.dto.IngestRequest;
import org.tornotron.echno_backend.modules.assistant.dto.IngestStatusDto;
import org.tornotron.echno_backend.modules.assistant.dto.QueryLogDto;
import org.tornotron.echno_backend.modules.assistant.ingest.DocumentIngestionService;
import org.tornotron.echno_backend.modules.assistant.service.AssistantService;

/**
 * The mobile surface of the Assistant: ask a question, and read back your own. Its twin
 * {@link AssistantControllerWeb} under {@code /web} exposes the same operations under the
 * same guards, and adds the organization's audit listing for the office.
 */
@RestController
@RequestMapping("/api/v1/assistant")
@RequireSubscription(feature = AssistantModule.FEATURE_KEY)
@AssistantModuleEnabled
@RequiredArgsConstructor
@Tag(name = "Assistant", description = "Mobile surface of the Assistant: ask a question about your projects, ingest documents, and read your history.")
public class AssistantController {

    private final AssistantService service;
    private final DocumentIngestionService ingestionService;

    @PostMapping("/ask")
    @PreAuthorize(AssistantModule.ASK_GUARD)
    @Operation(summary = "Ask the assistant a question",
            description = "Answers from the organization's own records, as the caller: only what the caller may read "
                    + "reaches the answer. The outcome says whether it is an answer, a question back, a decline "
                    + "that names what was looked for, an answer missing some sources, or unavailable.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The reply, whatever its outcome"),
            @ApiResponse(responseCode = "400", description = "The question is blank or too long"),
            @ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant, or the organization does not have the Assistant")
    })
    public AskResponse ask(@Valid @RequestBody AskRequest request) {
        return service.ask(request);
    }

    @GetMapping("/queries")
    @PreAuthorize(AssistantModule.ASK_GUARD)
    @Operation(summary = "List your own questions, newest first")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of the caller's own audited questions"),
            @ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant, or the organization does not have the Assistant")
    })
    public Page<QueryLogDto> queries(@Valid PageQuery20 page) {
        return service.history(page.getPageNo(), page.getPageSize());
    }

    @PostMapping("/ingest")
    @PreAuthorize(AssistantModule.INGEST_GUARD)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ingest a document into the assistant vector index",
            description = "Extracts text, segments into chunks with overlap, computes embeddings, and persists vector units for the project.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Document ingested successfully"),
            @ApiResponse(responseCode = "400", description = "Attachment id missing or invalid document format"),
            @ApiResponse(responseCode = "403", description = "Caller lacks project-manager or system-admin role"),
            @ApiResponse(responseCode = "404", description = "Attachment not found in caller's organization")
    })
    public ResponseEntity<IngestStatusDto> ingest(@Valid @RequestBody IngestRequest request) {
        IngestStatusDto status = ingestionService.ingest(request.attachmentId(), request.projectId());
        return ResponseEntity.status(HttpStatus.CREATED).body(status);
    }

    @GetMapping("/ingest/{attachmentId}/status")
    @PreAuthorize(AssistantModule.INGEST_GUARD)
    @Operation(summary = "Check ingestion status and chunk count of an attachment")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status returned"),
            @ApiResponse(responseCode = "403", description = "Caller lacks project-manager or system-admin role")
    })
    public ResponseEntity<IngestStatusDto> ingestStatus(@PathVariable Long attachmentId) {
        return ResponseEntity.ok(ingestionService.status(attachmentId));
    }
}
