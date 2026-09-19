package org.tornotron.echno_backend.modules.__MODULE_PKG__.web;

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
import org.tornotron.echno_backend.modules.__MODULE_PKG__.__MODULE_PASCAL__Module;
import org.tornotron.echno_backend.modules.__MODULE_PKG__.dto.Create__MODULE_PASCAL__EntryRequest;
import org.tornotron.echno_backend.modules.__MODULE_PKG__.dto.__MODULE_PASCAL__EntryDto;
import org.tornotron.echno_backend.modules.__MODULE_PKG__.service.__MODULE_PASCAL__Service;

/**
 * The web surface of the __MODULE_NAME__ module: the twin of {@link __MODULE_PASCAL__Controller}
 * under {@code /web}, where the office-side screens read and write.
 */
@RestController
@RequestMapping("/api/v1/__MODULE_ID__/web")
@RequireSubscription(feature = __MODULE_PASCAL__Module.FEATURE_KEY)
@RequiredArgsConstructor
@Tag(name = "__MODULE_NAME__ (web)", description = "Web twin of the __MODULE_NAME__ surface: reads for members, writes for admins.")
public class __MODULE_PASCAL__ControllerWeb {

    private final __MODULE_PASCAL__Service service;

    @GetMapping
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(summary = "List __MODULE_NAME__ entries, newest first")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "A page of entries"))
    public Page<__MODULE_PASCAL__EntryDto> list(@Valid PageQuery page) {
        return service.list(page.getPageNo(), page.getPageSize());
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(summary = "Get one __MODULE_NAME__ entry")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The entry"),
            @ApiResponse(responseCode = "404", description = "No such entry in the current tenant")
    })
    public __MODULE_PASCAL__EntryDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(summary = "Create a __MODULE_NAME__ entry")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Entry created"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<__MODULE_PASCAL__EntryDto> create(@Valid @RequestBody Create__MODULE_PASCAL__EntryRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }
}
