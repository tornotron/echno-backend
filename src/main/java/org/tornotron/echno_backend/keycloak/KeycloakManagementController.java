package org.tornotron.echno_backend.keycloak;

import org.springframework.security.access.prepost.PreAuthorize;
import io.prometheus.metrics.shaded.com_google_protobuf_4_28_3.Api;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.keycloak.dto.*;

import java.util.Map;

@RestController
@RequestMapping("api/v1/keycloak")
@Validated
public class KeycloakManagementController {

    private final KeycloakManagementService keycloakManagementService;

    public KeycloakManagementController(KeycloakManagementService keycloakManagementService) {
        this.keycloakManagementService = keycloakManagementService;
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/clients")
    public ResponseEntity<ApiResponse> createClient(@Valid @RequestBody ClientCreationDto dto) {
        String id = keycloakManagementService.createClient(dto);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse("Client created with ID: " + id));
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/clients/{clientId}/roles")
    public ResponseEntity<ApiResponse> addClientRole(@PathVariable String clientId, @Valid @RequestBody RoleCreationDto dto) {
        keycloakManagementService.addClientRole(clientId, dto);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse("Role added successfully"));
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/clients/{clientId}/authorization")
    public ResponseEntity<ApiResponse> enableAuthorization(@PathVariable String clientId, @RequestParam boolean enabled) {
        keycloakManagementService.enableAuthorizationServices(clientId, enabled);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse("Authorization services updated"));
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/clients/{clientId}/users/{userId}/roles")
    public ResponseEntity<ApiResponse> assignRoleToUser(@PathVariable String userId,@PathVariable String clientId, @RequestParam String roleName) {
        keycloakManagementService.assignClientRoleToUser(userId, clientId, roleName);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse("Role assigned to user successfully"));

    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/clients/{clientId}/resources")
    public ResponseEntity<ApiResponse> addResource(@PathVariable String clientId,@Valid @RequestBody ResourceDefinitionDto dto) {
        keycloakManagementService.createResource(clientId,dto);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse("Resource created successfully"));
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/clients/{clientId}/policies")
    public ResponseEntity<ApiResponse> addPolicy(@PathVariable String clientId,@Valid @RequestBody RolePolicyDefinitionDto dto) {
        keycloakManagementService.createRolePolicy(clientId,dto);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse("Policy created successfully"));
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/clients/{clientId}/policies/js")
    public ResponseEntity<ApiResponse> addJsPolicy(@PathVariable String clientId, @Valid @RequestBody JsPolicyDefinitionDto dto) {
        keycloakManagementService.createJsPolicy(clientId, dto);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse("JS policy created successfully"));
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/clients/{clientId}/permissions")
    public ResponseEntity<ApiResponse> addPermission(@PathVariable String clientId, @Valid @RequestBody PermissionDefinitionDto dto) {
        keycloakManagementService.createPermission(clientId,dto);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse("Permission created successfully"));
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/clients/{clientId}/authorization-setup")
    public ResponseEntity<ApiResponse> createWholeAuthorizationSetup(@PathVariable String clientId,@Valid @RequestBody AuthorizationSetupDto dto) {
        keycloakManagementService.configureAuthorization(clientId,dto);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse("Authorization setup created successfully"));
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/roles")
    public ResponseEntity<ApiResponse> addRealmRoles(@RequestBody Map<String, String> roles) {
        keycloakManagementService.addRealmRoles(roles);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse("Realm roles added successfully"));
    }
}
