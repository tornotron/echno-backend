package org.tornotron.echno_backend.common.module.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** One navigation intent of a module, as published to the web loader. */
public record ModuleNavDescriptorDto(
        String label,
        String section,
        String path,
        @Schema(nullable = true) String icon,
        List<String> requiredPermissions) {
}
