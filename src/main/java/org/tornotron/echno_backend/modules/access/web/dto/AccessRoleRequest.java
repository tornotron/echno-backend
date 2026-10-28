package org.tornotron.echno_backend.modules.access.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What a role is called and what it says about itself. Its grants and grantable roles are set
 * separately.
 *
 * @param name        what the screen shows; a new role's slug is made from it
 * @param subtitle    a short qualifier under the name, or null
 * @param description what the role is for, or null
 */
public record AccessRoleRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 120) String subtitle,
        @Size(max = 2000) String description) {
}
