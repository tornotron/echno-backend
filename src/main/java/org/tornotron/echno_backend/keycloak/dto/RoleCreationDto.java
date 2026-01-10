package org.tornotron.echno_backend.keycloak.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RoleCreationDto {

    @NotBlank(message = "Role 'name' is required")
    private String name;

    private String description;
}
