package org.tornotron.echno_backend.modules.sitenotes.web;

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
import org.tornotron.echno_backend.modules.sitenotes.SiteNotesModule;
import org.tornotron.echno_backend.modules.sitenotes.dto.CreateSiteNotesEntryRequest;
import org.tornotron.echno_backend.modules.sitenotes.dto.SiteNotesEntryDto;
import org.tornotron.echno_backend.modules.sitenotes.service.SiteNotesService;

/**
 * The web surface of the Site Notes module: the twin of {@link SiteNotesController}
 * under {@code /web}, where the office-side screens read and write.
 */
@RestController
@RequestMapping("/api/v1/site-notes/web")
@RequireSubscription(feature = SiteNotesModule.FEATURE_KEY)
@RequiredArgsConstructor
@Tag(name = "Site Notes (web)", description = "Web twin of the Site Notes surface: reads for members, writes for admins.")
public class SiteNotesControllerWeb {

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

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')")
    @Operation(summary = "Create a Site Notes entry")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Entry created"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<SiteNotesEntryDto> create(@Valid @RequestBody CreateSiteNotesEntryRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }
}
