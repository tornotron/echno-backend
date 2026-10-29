package org.tornotron.echno_backend.modules.access.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What a department or group is called and what it says about itself. Its members, roles and
 * grants are set separately.
 *
 * @param kind        {@code DEPARTMENT} or {@code GROUP}; null means {@code GROUP}
 * @param name        what the screen shows, unique in the organization
 * @param description what the group is for, or null
 */
public record AccessGroupRequest(
        String kind,
        @NotBlank @Size(max = 120) String name,
        @Size(max = 2000) String description) {
}
