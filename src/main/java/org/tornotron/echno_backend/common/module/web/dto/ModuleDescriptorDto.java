package org.tornotron.echno_backend.common.module.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * A module as the web app sees it. Consumed by {@code echno-core} and {@code echno-web}; the
 * field names are the contract, so rename them only together with both clients.
 *
 * <p>{@code enabled} is the installed-level state (the module is compiled in and its kill switch
 * is on). {@code entitled} is whether the caller's organization holds the module's feature. A
 * module is usable by the organization only when both are true and its dependencies are usable
 * too, which is exactly the set the {@code /enabled} route returns.
 */
public record ModuleDescriptorDto(
        String id,
        String name,
        String version,
        @Schema(nullable = true) String entitlementFeatureKey,
        boolean enabled,
        boolean entitled,
        List<ModuleNavDescriptorDto> nav,
        List<String> permissions) {
}
