package org.tornotron.echno_backend.modules.sitenotes.web;

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
import org.tornotron.echno_backend.modules.sitenotes.SiteNotesModule;
import org.tornotron.echno_backend.modules.sitenotes.dto.SiteNotesEntryDto;
import org.tornotron.echno_backend.modules.sitenotes.service.SiteNotesService;

/**
 * The mobile surface of the Site Notes module: reads only, for the site app. Its twin
 * {@link SiteNotesControllerWeb} carries the writes under {@code /web}. Both are
 * gated on the module's feature key at class level and every method names its own guard.
 */
@RestController
@RequestMapping("/api/v1/site-notes")
@RequireSubscription(feature = SiteNotesModule.FEATURE_KEY)
@RequiredArgsConstructor
@Tag(name = "Site Notes", description = "Site Notes entries of the current tenant, gated on the module.")
public class SiteNotesController {

    private final SiteNotesService service;

    @GetMapping
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(summary = "List Site Notes entries, newest first")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "A page of entries"))
    public Page<SiteNotesEntryDto> list(@Valid PageQuery page) {
        return service.list(page.getPageNo(), page.getPageSize());
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(summary = "Get one Site Notes entry")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The entry"),
            @ApiResponse(responseCode = "404", description = "No such entry in the current tenant")
    })
    public SiteNotesEntryDto get(@PathVariable UUID id) {
        return service.get(id);
    }
}
