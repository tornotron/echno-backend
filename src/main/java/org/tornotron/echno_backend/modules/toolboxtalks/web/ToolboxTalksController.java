package org.tornotron.echno_backend.modules.toolboxtalks.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.modules.toolboxtalks.ToolboxTalksModule;
import org.tornotron.echno_backend.modules.toolboxtalks.domain.ToolboxTalkStatus;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.ToolboxTalkDto;
import org.tornotron.echno_backend.modules.toolboxtalks.service.ToolboxTalksService;

/**
 * The mobile surface of the Toolbox Talks module: reads only, for the site app. Its twin
 * {@link ToolboxTalksControllerWeb} carries the writes under {@code /web}. Both are
 * gated on the module's feature key at class level and every method names its own guard.
 */
@RestController
@RequestMapping("/api/v1/toolbox-talks")
@RequireSubscription(feature = ToolboxTalksModule.FEATURE_KEY)
@RequiredArgsConstructor
@Tag(name = "Toolbox Talks", description = "Toolbox Talks entries of the current tenant, gated on the module.")
public class ToolboxTalksController {

    private final ToolboxTalksService service;

    @GetMapping
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(summary = "List toolbox talks, newest first")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "A page of talks"))
    public Page<ToolboxTalkDto> list(@RequestParam(required = false) Long projectId,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                     @RequestParam(required = false) ToolboxTalkStatus status,
                                     @Valid PageQuery page) {
        return service.list(projectId, from, to, status, page.getPageNo(), page.getPageSize());
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(summary = "Get one toolbox talk")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The talk"),
            @ApiResponse(responseCode = "404", description = "No such talk in the current tenant")
    })
    public ToolboxTalkDto get(@PathVariable UUID id) {
        return service.get(id);
    }
}
