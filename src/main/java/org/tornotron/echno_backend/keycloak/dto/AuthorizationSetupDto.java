package org.tornotron.echno_backend.keycloak.dto;

import lombok.Data;
import java.util.List;

@Data
public class AuthorizationSetupDto {
    private List<ResourceDefinitionDto> resources;
    private List<RolePolicyDefinitionDto> policies;
    private List<PermissionDefinitionDto> permissions;
}
