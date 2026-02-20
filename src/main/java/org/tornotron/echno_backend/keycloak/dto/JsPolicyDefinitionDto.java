package org.tornotron.echno_backend.keycloak.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class JsPolicyDefinitionDto {

    @NotBlank(message = "Policy 'name' is required")
    private String name;

    private String description;

    @NotBlank(message = "Policy 'code' is required")
    private String code;
}
