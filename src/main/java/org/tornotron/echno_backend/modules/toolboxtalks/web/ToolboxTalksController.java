package org.tornotron.echno_backend.modules.toolboxtalks.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.modules.toolboxtalks.ToolboxTalksModule;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.ToolboxTalksEntryDto;
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
    @Operation(summary = "List Toolbox Talks entries, newest first")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "A page of entries"))
    public Page<ToolboxTalksEntryDto> list(@Valid PageQuery page) {
        return service.list(page.getPageNo(), page.getPageSize());
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(summary = "Get one Toolbox Talks entry")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The entry"),
            @ApiResponse(responseCode = "404", description = "No such entry in the current tenant")
    })
    public ToolboxTalksEntryDto get(@PathVariable UUID id) {
        return service.get(id);
    }
}
