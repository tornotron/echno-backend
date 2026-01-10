package org.tornotron.echno_backend.keycloak.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.Set;

@Data
public class PermissionDefinitionDto {
    @NotBlank(message = "Permission 'name' is required")
    private String name;

    private String description;

    @NotNull(message = "policies cannot be null, use empty set if needed")
    private Set<String> policies; // Names of policies

    @NotNull(message = "resources cannot be null")
    private Set<String> resources; // Names of resources

    @NotNull(message = "scopes cannot be null, use empty set if needed")
    private Set<String> scopes; // Names of scopes
}
