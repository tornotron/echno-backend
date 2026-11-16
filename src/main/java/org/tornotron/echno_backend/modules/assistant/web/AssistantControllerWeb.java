package org.tornotron.echno_backend.modules.assistant.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.common.pagination.PageQuery20;
import org.tornotron.echno_backend.modules.assistant.AssistantModule;
import org.tornotron.echno_backend.modules.assistant.AssistantModuleEnabled;
import org.tornotron.echno_backend.modules.assistant.dto.AskRequest;
import org.tornotron.echno_backend.modules.assistant.dto.AskResponse;
import org.tornotron.echno_backend.modules.assistant.dto.QueryLogDto;
import org.tornotron.echno_backend.modules.assistant.service.AssistantService;

/**
 * The web surface of the Assistant: the twin of {@link AssistantController} under {@code /web},
 * with the same two operations under the same guards, plus the audit listing of every question
 * asked in the organization, which only the system admin may read.
 */
@RestController
@RequestMapping("/api/v1/assistant/web")
@RequireSubscription(feature = AssistantModule.FEATURE_KEY)
@AssistantModuleEnabled
@RequiredArgsConstructor
@Tag(name = "Assistant (web)", description = "Web twin of the Assistant surface, with the organization's audit listing for the system admin.")
public class AssistantControllerWeb {

    private final AssistantService service;

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

    @GetMapping("/audit")
    @PreAuthorize(AssistantModule.ADMIN_GUARD)
    @Operation(summary = "List every question asked in the organization, newest first",
            description = "The audit trail: who asked what, which sources were read, how it ended and what it cost.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of audited questions"),
            @ApiResponse(responseCode = "403", description = "Caller is not a system admin of the current tenant, or the organization does not have the Assistant")
    })
    public Page<QueryLogDto> audit(@Valid PageQuery20 page) {
        return service.audit(page.getPageNo(), page.getPageSize());
    }
}
