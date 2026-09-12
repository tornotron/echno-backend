package org.tornotron.echno_backend.common.module.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.module.ModuleManifest;
import org.tornotron.echno_backend.common.module.ModuleRegistry;
import org.tornotron.echno_backend.common.module.NavDescriptor;
import org.tornotron.echno_backend.common.module.web.dto.ModuleDescriptorDto;
import org.tornotron.echno_backend.common.module.web.dto.ModuleNavDescriptorDto;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;

import java.util.List;

/**
 * The module registry as the web app reads it. {@code /enabled} is what the web loader calls on
 * session bootstrap to learn which modules to mount; the bare route is the operator's view of
 * everything installed.
 */
@RestController
@RequestMapping("/api/v1/modules/web")
@Tag(
        name = "Modules (Web)",
        description = "Lists the modules compiled into this build and the subset enabled for the "
                + "caller's organization, with the navigation and permission keys each publishes."
)
public class ModuleControllerWeb {

    private final ModuleRegistry registry;

    public ModuleControllerWeb(ModuleRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "List installed modules",
            description = "Every module compiled into this build, with whether its kill switch is on "
                    + "and whether the caller's organization is entitled to it."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Installed modules"),
            @ApiResponse(responseCode = "403", description = "Caller is not a system admin of the current tenant")
    })
    public List<ModuleDescriptorDto> installed() {
        Long organizationId = TenantContext.getCurrentOrgId();
        return registry.installed().stream()
                .map(manifest -> describe(manifest, organizationId))
                .toList();
    }

    @GetMapping("/enabled")
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @Operation(
            summary = "List modules enabled for the caller's organization",
            description = "The modules that are installed, switched on and entitled for the current "
                    + "tenant, with their dependencies satisfied. The web loader mounts exactly these."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Enabled modules"),
            @ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant")
    })
    public List<ModuleDescriptorDto> enabled() {
        Long organizationId = TenantContext.getCurrentOrgId();
        if (organizationId == null) {
            return List.of();
        }
        return registry.enabledForOrg(organizationId).stream()
                .map(manifest -> describe(manifest, organizationId))
                .toList();
    }

    private ModuleDescriptorDto describe(ModuleManifest manifest, Long organizationId) {
        String id = manifest.id();
        return new ModuleDescriptorDto(
                id,
                manifest.name(),
                manifest.version(),
                manifest.entitlementFeatureKey(),
                registry.isSwitchedOn(id),
                organizationId != null && registry.isEntitled(id, organizationId),
                registry.navDescriptors(id).stream().map(ModuleControllerWeb::describe).toList(),
                registry.permissions(id));
    }

    private static ModuleNavDescriptorDto describe(NavDescriptor nav) {
        return new ModuleNavDescriptorDto(
                nav.label(), nav.section(), nav.path(), nav.icon(), nav.requiredPermissions());
    }
}
