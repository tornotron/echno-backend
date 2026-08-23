package org.tornotron.echno_backend.keycloak;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
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
@Tag(
        name = "Keycloak Management",
        description = "Low-level Keycloak administration: registering clients, realm and client roles, "
                + "authorization services, resources, policies and permissions. These calls talk "
                + "directly to the Keycloak admin API and are restricted to a system admin."
)
public class KeycloakManagementController {

    private final KeycloakManagementService keycloakManagementService;

    public KeycloakManagementController(KeycloakManagementService keycloakManagementService) {
        this.keycloakManagementService = keycloakManagementService;
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/clients")
    @Operation(
            summary = "Create a Keycloak client",
            description = "Registers a new confidential client in Keycloak from the given definition."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Client created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<ApiResponse> createClient(@Valid @RequestBody ClientCreationDto dto) {
        String id = keycloakManagementService.createClient(dto);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse("Client created with ID: " + id));
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/clients/{clientId}/roles")
    @Operation(
            summary = "Add a client role",
            description = "Creates a new role scoped to the given Keycloak client."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Role added"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No client with the given id")
    })
    public ResponseEntity<ApiResponse> addClientRole(@PathVariable String clientId, @Valid @RequestBody RoleCreationDto dto) {
        keycloakManagementService.addClientRole(clientId, dto);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse("Role added successfully"));
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/clients/{clientId}/authorization")
    @Operation(
            summary = "Toggle client authorization services",
            description = "Enables or disables fine-grained authorization services on the given client."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Authorization services updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No client with the given id")
    })
    public ResponseEntity<ApiResponse> enableAuthorization(@PathVariable String clientId, @RequestParam boolean enabled) {
        keycloakManagementService.enableAuthorizationServices(clientId, enabled);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse("Authorization services updated"));
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/clients/{clientId}/users/{userId}/roles")
    @Operation(
            summary = "Assign a client role to a user",
            description = "Grants the named client role to the given Keycloak user."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Role assigned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No client, user or role with the given identifiers")
    })
    public ResponseEntity<ApiResponse> assignRoleToUser(@PathVariable String userId,@PathVariable String clientId, @RequestParam String roleName) {
        keycloakManagementService.assignClientRoleToUser(userId, clientId, roleName);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse("Role assigned to user successfully"));

    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/clients/{clientId}/resources")
    @Operation(
            summary = "Add an authorization resource",
            description = "Registers a protected resource under the client's authorization services."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Resource created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No client with the given id")
    })
    public ResponseEntity<ApiResponse> addResource(@PathVariable String clientId,@Valid @RequestBody ResourceDefinitionDto dto) {
        keycloakManagementService.createResource(clientId,dto);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse("Resource created successfully"));
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/clients/{clientId}/policies")
    @Operation(
            summary = "Add a role-based authorization policy",
            description = "Registers a policy that grants access to callers holding the given roles."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Policy created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No client with the given id")
    })
    public ResponseEntity<ApiResponse> addPolicy(@PathVariable String clientId,@Valid @RequestBody RolePolicyDefinitionDto dto) {
        keycloakManagementService.createRolePolicy(clientId,dto);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse("Policy created successfully"));
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/clients/{clientId}/policies/js")
    @Operation(
            summary = "Add a JS-scripted authorization policy",
            description = "Registers a policy whose decision logic is a JavaScript rule evaluated by Keycloak."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Policy created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No client with the given id")
    })
    public ResponseEntity<ApiResponse> addJsPolicy(@PathVariable String clientId, @Valid @RequestBody JsPolicyDefinitionDto dto) {
        keycloakManagementService.createJsPolicy(clientId, dto);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse("JS policy created successfully"));
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/clients/{clientId}/permissions")
    @Operation(
            summary = "Add an authorization permission",
            description = "Ties a resource to one or more policies to form an enforceable permission."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Permission created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No client with the given id")
    })
    public ResponseEntity<ApiResponse> addPermission(@PathVariable String clientId, @Valid @RequestBody PermissionDefinitionDto dto) {
        keycloakManagementService.createPermission(clientId,dto);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse("Permission created successfully"));
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/clients/{clientId}/authorization-setup")
    @Operation(
            summary = "Create a full authorization setup",
            description = "Creates resources, policies and permissions for a client in one call, from a "
                    + "single combined definition."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Authorization setup created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A field failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No client with the given id")
    })
    public ResponseEntity<ApiResponse> createWholeAuthorizationSetup(@PathVariable String clientId,@Valid @RequestBody AuthorizationSetupDto dto) {
        keycloakManagementService.configureAuthorization(clientId,dto);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse("Authorization setup created successfully"));
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/roles")
    @Operation(
            summary = "Add realm roles",
            description = "Creates one or more realm-level roles from a map of role name to description."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Realm roles added"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<ApiResponse> addRealmRoles(@RequestBody Map<String, String> roles) {
        keycloakManagementService.addRealmRoles(roles);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse("Realm roles added successfully"));
    }
}
