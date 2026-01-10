package org.tornotron.echno_backend.keycloak.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.Set;

@Data
public class RolePolicyDefinitionDto {

    @NotBlank(message = "Policy 'name' is required")
    private String name;

    private String description;

    private Set<String> roles;
}
