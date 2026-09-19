package org.tornotron.echno_backend.modules.toolboxtalks.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.customAnnotation.RequireSubscription;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.modules.toolboxtalks.ToolboxTalksModule;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.CreateToolboxTalksEntryRequest;
import org.tornotron.echno_backend.modules.toolboxtalks.dto.ToolboxTalksEntryDto;
import org.tornotron.echno_backend.modules.toolboxtalks.service.ToolboxTalksService;

/**
 * The web surface of the Toolbox Talks module: the twin of {@link ToolboxTalksController}
 * under {@code /web}, where the office-side screens read and write.
 */
@RestController
@RequestMapping("/api/v1/toolbox-talks/web")
@RequireSubscription(feature = ToolboxTalksModule.FEATURE_KEY)
@RequiredArgsConstructor
@Tag(name = "Toolbox Talks (web)", description = "Web twin of the Toolbox Talks surface: reads for members, writes for admins.")
public class ToolboxTalksControllerWeb {

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

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(summary = "Create a Toolbox Talks entry")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Entry created"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<ToolboxTalksEntryDto> create(@Valid @RequestBody CreateToolboxTalksEntryRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }
}
