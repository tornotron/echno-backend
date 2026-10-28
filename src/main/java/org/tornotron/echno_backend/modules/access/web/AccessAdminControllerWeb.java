package org.tornotron.echno_backend.modules.access.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Arrays;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.matrix.catalog.AccessCatalog;
import org.tornotron.echno_backend.modules.access.web.dto.AccessCatalogDto;

/**
 * The Roles &amp; Permissions screen: the catalog it draws the matrix from, and the organization's
 * roles, groups and people.
 *
 * <p>Reading needs {@code access:view}. Everything here is the organization the request is scoped
 * to.
 */
@RestController
@RequestMapping("/api/v1/access/web")
@Tag(
        name = "Access Administration (Web)",
        description = "The Roles & Permissions screen: the areas and actions roles can grant, and the "
                + "organization's roles, departments and groups, and people's access."
)
public class AccessAdminControllerWeb {

    static final String VIEW_GUARD = "@access.can('access', 'view')";

    @GetMapping("/catalog")
    @PreAuthorize(VIEW_GUARD)
    @Operation(
            summary = "Get the access catalog",
            description = "The sections, areas and actions the matrix shows, and the scopes a grant can "
                    + "carry. A permission is written <area>:<action>, for example wbs:edit."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The catalog"),
            @ApiResponse(responseCode = "403", description = "Caller may not view roles and permissions")
    })
    public AccessCatalogDto getAccessCatalog() {
        return new AccessCatalogDto(
                AccessCatalog.SECTIONS,
                AccessCatalog.areas().stream()
                        .map(area -> new AccessCatalogDto.AreaDto(area.id(), area.label(), area.section(), area.actions()))
                        .toList(),
                Arrays.stream(AccessScope.values()).map(AccessScope::name).toList());
    }
}
