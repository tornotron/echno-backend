package org.tornotron.echno_backend.keycloak.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.Set;

@Data
public class ResourceDefinitionDto {
    @NotBlank(message = "Resource 'name' is required")
    private String name;

    @NotBlank(message = "Resource 'displayName' is required")
    private String displayName;

    private String type;

    private Set<String> uris;

    private Set<String> scopes;
}
