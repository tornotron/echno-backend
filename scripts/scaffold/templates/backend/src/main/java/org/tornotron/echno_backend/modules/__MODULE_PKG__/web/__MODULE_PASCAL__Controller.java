package org.tornotron.echno_backend.modules.__MODULE_PKG__.web;

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
import org.tornotron.echno_backend.modules.__MODULE_PKG__.__MODULE_PASCAL__Module;
import org.tornotron.echno_backend.modules.__MODULE_PKG__.dto.__MODULE_PASCAL__EntryDto;
import org.tornotron.echno_backend.modules.__MODULE_PKG__.service.__MODULE_PASCAL__Service;

/**
 * The mobile surface of the __MODULE_NAME__ module: reads only, for the site app. Its twin
 * {@link __MODULE_PASCAL__ControllerWeb} carries the writes under {@code /web}. Both are
 * gated on the module's feature key at class level and every method names its own guard.
 */
@RestController
@RequestMapping("/api/v1/__MODULE_ID__")
@RequireSubscription(feature = __MODULE_PASCAL__Module.FEATURE_KEY)
@RequiredArgsConstructor
@Tag(name = "__MODULE_NAME__", description = "__MODULE_NAME__ entries of the current tenant, gated on the module.")
public class __MODULE_PASCAL__Controller {

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
}
