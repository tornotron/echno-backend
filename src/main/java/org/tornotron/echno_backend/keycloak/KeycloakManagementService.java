package org.tornotron.echno_backend.keycloak;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.*;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.authorization.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.keycloak.dto.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class KeycloakManagementService {

    private final Keycloak keycloak;

    @Value("${keycloak-initializer.application-realm}")
    private String realm;

    public KeycloakManagementService(Keycloak keycloak) {
        this.keycloak = keycloak;
    }

    public String createClient(ClientCreationDto dto) {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(dto.getClientId());
        client.setName(dto.getName());
        client.setDescription(dto.getDescription());
        client.setRootUrl(dto.getRootUrl());
        client.setAdminUrl(dto.getAdminUrl());
        client.setBaseUrl(dto.getBaseUrl());
        client.setRedirectUris(dto.getRedirectUris());
        client.setWebOrigins(dto.getWebOrigins());
        client.setPublicClient(dto.isPublicClient());
        client.setBearerOnly(dto.isBearerOnly());
        client.setServiceAccountsEnabled(dto.isServiceAccountsEnabled());
        client.setStandardFlowEnabled(dto.isStandardFlowEnabled());
        client.setImplicitFlowEnabled(dto.isImplicitFlowEnabled());
        client.setDirectAccessGrantsEnabled(dto.isDirectAccessGrantsEnabled());

        ClientsResource clientsResource = keycloak.realm(realm).clients();
        try (Response response = clientsResource.create(client)) {
            if (response.getStatus() == 201) {
                String location = response.getHeaderString("Location");
                String id = location.substring(location.lastIndexOf('/') + 1);
                log.info("Client created successfully with ID: {}", id);
                return id;
            } else {
                log.error("Failed to create client. Status: {}", response.getStatus());
                throw new RuntimeException("Failed to create client: " + response.getStatusInfo().getReasonPhrase());
            }
        }
    }

    public void addClientRole(String clientId, RoleCreationDto dto) {
        ClientResource clientResource = getClientResource(clientId);

        RoleRepresentation role = new RoleRepresentation();
        role.setName(dto.getName());
        role.setDescription(dto.getDescription());

        clientResource.roles().create(role);
        log.info("Role {} added to client {}", dto.getName(), clientId);
    }

    public void addRealmRole(String roleName, String description) {
        RoleRepresentation role = new RoleRepresentation();
        role.setName(roleName);
        role.setDescription(description);

        keycloak.realm(realm).roles().create(role);
        log.info("Realm role {} added", roleName);
    }

    public void addRealmRoles(Map<String, String> roles) {
        if (roles != null) {
            roles.forEach(this::addRealmRole);
        }
    }

    public void enableAuthorizationServices(String clientId, boolean enabled) {
        ClientResource clientResource = getClientResource(clientId);
        ClientRepresentation representation = clientResource.toRepresentation();
        representation.setAuthorizationServicesEnabled(enabled);
        representation.setServiceAccountsEnabled(true); // Required for authz
        clientResource.update(representation);
        log.info("Authorization services {} for client {}", enabled ? "enabled" : "disabled", clientId);
    }

    public void assignRealmRoleToUser(String userId, String roleName) {
        UserResource userResource = getUserResource(userId);
        try {
            userResource.toRepresentation();
        } catch (NotFoundException e) {
            throw new RuntimeException("User not found: " + userId);
        }

        RoleRepresentation role;
        try {
            role = keycloak.realm(realm).roles().get(roleName).toRepresentation();
        } catch (NotFoundException e) {
            throw new RuntimeException("Realm role '" + roleName + "' not found");
        }

        userResource.roles().realmLevel().add(Collections.singletonList(role));
        log.info("Realm role {} assigned to user {}", roleName, userId);
    }

    public void assignClientRoleToUser(String userId, String clientId, String roleName) {
        UserResource userResource = getUserResource(userId);
        try {
            userResource.toRepresentation();
        } catch (NotFoundException e) {
            throw new RuntimeException("User not found: " + userId);
        }

        ClientResource clientResource = getClientResource(clientId);

        RoleRepresentation role;
        try {
            role = clientResource.roles().get(roleName).toRepresentation();
        } catch (NotFoundException e) {
            throw new RuntimeException("Role '" + roleName + "' not found in client '" + clientId + "'");
        }

        String clientUuid = clientResource.toRepresentation().getId();
        userResource.roles().clientLevel(clientUuid).add(Collections.singletonList(role));
        log.info("Client role {} of client {} assigned to user {}", roleName, clientId, userId);
    }

    private UserResource getUserResource(String userId) {
        return keycloak.realm(realm).users().get(userId);
    }

    private ClientResource getClientResource(String clientId) {
        ClientsResource clientsResource = keycloak.realm(realm).clients();
        List<ClientRepresentation> clients = clientsResource.findByClientId(clientId);
        if (clients.isEmpty()) {
            throw new RuntimeException("Client not found: " + clientId);
        }
        return clientsResource.get(clients.getFirst().getId());
    }

    // --- Authorization Services ---

    public void configureAuthorization(String clientId, AuthorizationSetupDto dto) {
        if (dto.getResources() != null) {
            dto.getResources().forEach(r -> createResource(clientId, r));
        }
        if (dto.getPolicies() != null) {
            dto.getPolicies().forEach(p -> createRolePolicy(clientId, p));
        }
        if (dto.getPermissions() != null) {
            dto.getPermissions().forEach(p -> createPermission(clientId, p));
        }
    }

    public void createResource(String clientId, ResourceDefinitionDto dto) {
        AuthorizationResource authz = getClientResource(clientId).authorization();

        ResourceRepresentation resource = new ResourceRepresentation();
        resource.setName(dto.getName());
        resource.setDisplayName(dto.getDisplayName());
        resource.setType(dto.getType());
        resource.setUris(dto.getUris());
        resource.setScopes(dto.getScopes() != null ? dto.getScopes().stream()
                .map(ScopeRepresentation::new)
                .collect(Collectors.toSet()) : null);

        try (Response response = authz.resources().create(resource)) {
            if (response.getStatus() != 201 && response.getStatus() != 409) { // 409 = Conflict (already exists)
                log.error("Failed to create resource {}. Status: {}", dto.getName(), response.getStatus());
                throw new RuntimeException("Failed to create resource: " + dto.getName());
            } else if (response.getStatus() == 409) {
                log.info("Resource {} already exists for client {}", dto.getName(), clientId);
            } else {
                log.info("Resource {} created for client {}", dto.getName(), clientId);
            }
        }
    }

    public void createRolePolicy(String clientId, RolePolicyDefinitionDto dto) {
        AuthorizationResource authz = getClientResource(clientId).authorization();

        RolePolicyRepresentation policy = new RolePolicyRepresentation();
        policy.setName(dto.getName());
        policy.setDescription(dto.getDescription());

        if (dto.getRoles() != null) {
            for (String roleName : dto.getRoles()) {
                RoleRepresentation roleRep = null;
                try {
                    // Try to find role in realm by name
                    roleRep = keycloak.realm(realm).roles().get(roleName).toRepresentation();
                } catch (Exception e) {
                    log.warn("Role '{}' not found in realm during policy creation", roleName);
                }

                if (roleRep != null) {
                    // Defaulting required to true as the DTO is a Set<String>
                    policy.addRole(roleRep.getId(), true);
                } else {
                    log.warn("Role '{}' not found, skipping for policy '{}'", roleName, dto.getName());
                }
            }
        }

        executeAuthzCreation(clientId, dto.getName(), "policy", () -> authz.policies().role().create(policy));
    }

    public void createJsPolicy(String clientId, JsPolicyDefinitionDto dto) {
        AuthorizationResource authz = getClientResource(clientId).authorization();

        JSPolicyRepresentation policy = new JSPolicyRepresentation();
        policy.setName(dto.getName());
        policy.setDescription(dto.getDescription());
        policy.setCode(dto.getCode());

        executeAuthzCreation(clientId, dto.getName(), "js-policy", () -> authz.policies().js().create(policy));
    }

    public void createPermission(String clientId, PermissionDefinitionDto dto) {
        AuthorizationResource authz = getClientResource(clientId).authorization();

        if (dto.getScopes() != null && !dto.getScopes().isEmpty()) {
            ScopePermissionRepresentation permission = new ScopePermissionRepresentation();
            permission.setName(dto.getName());
            permission.setDescription(dto.getDescription());
            permission.setResources(dto.getResources());
            permission.setPolicies(dto.getPolicies());
            permission.setScopes(dto.getScopes());

            executeAuthzCreation(clientId, dto.getName(), "scope-based permission", () -> authz.permissions().scope().create(permission));
        } else {
            ResourcePermissionRepresentation permission = new ResourcePermissionRepresentation();
            permission.setName(dto.getName());
            permission.setDescription(dto.getDescription());
            permission.setResources(dto.getResources());
            permission.setPolicies(dto.getPolicies());

            executeAuthzCreation(clientId, dto.getName(), "resource-based permission", () -> authz.permissions().resource().create(permission));
        }
    }

    private void executeAuthzCreation(String clientId, String name, String type, java.util.function.Supplier<Response> creator) {
        try (Response response = creator.get()) {
            if (response.getStatus() != 201 && response.getStatus() != 409) {
                log.error("Failed to create {} {}. Status: {}", type, name, response.getStatus());
                throw new RuntimeException("Failed to create " + type + ": " + name);
            } else if (response.getStatus() == 409) {
                log.info("{} {} already exists for client {}", type, name, clientId);
            } else {
                log.info("{} {} created for client {}", type, name, clientId);
            }
        }
    }
}

