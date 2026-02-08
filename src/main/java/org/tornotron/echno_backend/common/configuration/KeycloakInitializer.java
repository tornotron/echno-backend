package org.tornotron.echno_backend.common.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.user.dto.UserKeycloakDto;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@DependsOn("keycloakConfigGenerator")
public class KeycloakInitializer implements InitializingBean {

    private final Keycloak keycloak;

    private final KeycloakInitializerConfigurationProperties keycloakInitializerConfigurationProperties;

    private final ObjectMapper mapper;

    private static String REALM_ID;

    @Value("${keycloak.config.output-path}")
    private String configOutput;

    @Value("${keycloak.client-id}")
    private String appClientId;

    public KeycloakInitializer(Keycloak keycloak,
                               KeycloakInitializerConfigurationProperties keycloakInitializerConfigurationProperties,
                               ObjectMapper objectMapper) {
        this.keycloak = keycloak;
        this.keycloakInitializerConfigurationProperties = keycloakInitializerConfigurationProperties;
        this.mapper = objectMapper;
    }


    @Override
    public void afterPropertiesSet() throws Exception {

        REALM_ID = keycloakInitializerConfigurationProperties.getApplicationRealm();

        if (keycloakInitializerConfigurationProperties.isInitializeOnStartup()) {
            init(false);
        }

    }

    public void init(boolean overwrite) {

        log.info("Initializer start");

        boolean isAlreadyInitialized;
        try {
            keycloak.realm(REALM_ID).toRepresentation();
            isAlreadyInitialized = true;
        } catch (NotFoundException e) {
            isAlreadyInitialized = false;
        }

        if(isAlreadyInitialized && overwrite) {
            reset();
        }

        if (!isAlreadyInitialized || overwrite) {

            initKeycloak();

            log.info("Keycloak initialized successfully");
        } else {

            log.warn("Keycloak initialization cancelled: realm already exists");
            // Sync client configuration from JSON even if realm exists
            syncClientConfiguration();
            // Ensure service account roles are assigned even if realm exists
            assignServiceAccountRoles();
        }
    }

    private void syncClientConfiguration() {
        try {
            log.info("Syncing client configuration for '{}'", appClientId);
            
            Path configFile = Paths.get(configOutput, "init-keycloak.json");
            if (!Files.exists(configFile)) {
                log.warn("Config file not found at: " + configFile + ". Skipping client sync.");
                return;
            }

            RealmRepresentation realmRep = mapper.readValue(configFile.toFile(), RealmRepresentation.class);
            org.keycloak.representations.idm.ClientRepresentation clientFromConfig = realmRep.getClients().stream()
                    .filter(c -> appClientId.equals(c.getClientId()))
                    .findFirst()
                    .orElse(null);

            if (clientFromConfig == null) {
                log.warn("Client '{}' not found in configuration file.", appClientId);
                return;
            }

            List<org.keycloak.representations.idm.ClientRepresentation> existingClients = keycloak.realm(REALM_ID).clients().findByClientId(appClientId);
            if (existingClients.isEmpty()) {
                log.warn("Client '{}' not found in Keycloak. Skipping sync.", appClientId);
                return;
            }

            org.keycloak.representations.idm.ClientRepresentation existingClient = existingClients.get(0);
            org.keycloak.admin.client.resource.ClientResource clientResource = keycloak.realm(REALM_ID).clients().get(existingClient.getId());

            // Update specific fields from config to existing client
            // We preserve the ID and Secret from the existing client
            clientFromConfig.setId(existingClient.getId());
            clientFromConfig.setSecret(existingClient.getSecret());
            
            clientResource.update(clientFromConfig);
            log.info("Client '{}' configuration synced successfully.", appClientId);

        } catch (Exception e) {
            log.error("Failed to sync client configuration: {}", e.getMessage());
        }
    }

    private void initKeycloak() {

        initKeycloakRealm();
        initKeycloakUsers();
        assignServiceAccountRoles();

    }

    private void assignServiceAccountRoles() {
        try {
            log.info("Assigning service account roles for client '{}'", appClientId);

            // 1. Find the client UUID (not clientId) for the application client
            List<org.keycloak.representations.idm.ClientRepresentation> clients = keycloak.realm(REALM_ID).clients().findByClientId(appClientId);
            if (clients.isEmpty()) {
                log.error("Client '{}' not found in realm '{}'", appClientId, REALM_ID);
                return;
            }
            org.keycloak.representations.idm.ClientRepresentation appClientRep = clients.get(0);
            org.keycloak.admin.client.resource.ClientResource appClientResource = keycloak.realm(REALM_ID).clients().get(appClientRep.getId());

            // 2. Enable Service Accounts if not enabled
            if (!Boolean.TRUE.equals(appClientRep.isServiceAccountsEnabled())) {
                appClientRep.setServiceAccountsEnabled(true);
                appClientResource.update(appClientRep);
                log.info("Enabled service accounts for client '{}'", appClientId);
            }

            // 3. Get the Service Account User
            UserRepresentation serviceAccountUser = appClientResource.getServiceAccountUser();
            if (serviceAccountUser == null) {
                log.error("Service account user not found for client '{}'", appClientId);
                return;
            }
            UserResource serviceAccountUserResource = keycloak.realm(REALM_ID).users().get(serviceAccountUser.getId());

            // 4. Find the 'realm-management' client UUID
            List<org.keycloak.representations.idm.ClientRepresentation> realmMgmtClients = keycloak.realm(REALM_ID).clients().findByClientId("realm-management");
            if (realmMgmtClients.isEmpty()) {
                log.error("'realm-management' client not found!");
                return;
            }
            org.keycloak.representations.idm.ClientRepresentation realmMgmtClientRep = realmMgmtClients.get(0);

            // 5. Get the required roles from 'realm-management'
            String[] requiredRoles = {"manage-users", "view-users", "manage-groups", "query-groups", "query-users"};
            List<RoleRepresentation> rolesToAdd = new ArrayList<>();

            for (String roleName : requiredRoles) {
                try {
                    RoleRepresentation role = keycloak.realm(REALM_ID).clients().get(realmMgmtClientRep.getId()).roles().get(roleName).toRepresentation();
                    rolesToAdd.add(role);
                } catch (Exception e) {
                    log.warn("Role '{}' not found in realm-management", roleName);
                }
            }

            // 6. Assign roles to the service account user
            if (!rolesToAdd.isEmpty()) {
                serviceAccountUserResource.roles().clientLevel(realmMgmtClientRep.getId()).add(rolesToAdd);
                log.info("Assigned realm-management roles {} to service account for '{}'", rolesToAdd.stream().map(RoleRepresentation::getName).toList(), appClientId);
            }

        } catch (Exception e) {
            log.error("Failed to assign service account roles: {}", e.getMessage(), e);
        }
    }



    private void initKeycloakRealm() {
        try {
            Path configFile = Paths.get(configOutput,"init-keycloak.json");

            if (!Files.exists(configFile)) {
                throw new FileNotFoundException("Config file not found at: "+configFile);
            }

            RealmRepresentation realmRepresentationToImport =
                    mapper.readValue(configFile.toFile(), RealmRepresentation.class);
            
            realmRepresentationToImport.setRealm(REALM_ID);
            realmRepresentationToImport.setId(REALM_ID);

            keycloak.realms().create(realmRepresentationToImport);
        } catch (IOException e) {
            String errorMessage = String.format("Failed to import keycloak realm representation : %s", e.getMessage());
            log.error(errorMessage);
            throw new RuntimeException(errorMessage, e);
        }
    }

    private void initKeycloakUsers() {
        List<UserKeycloakDto> users = null;
        try {
            Path configFile = Paths.get(configOutput,"init-keycloak-users.json");

            if(!Files.exists(configFile)) {
                throw new FileNotFoundException("Config file not found at: "+configFile);
            }


            users = mapper.readValue(
                    configFile.toFile(),
                    mapper.getTypeFactory().constructCollectionType(ArrayList.class, UserKeycloakDto.class)
            );
        } catch (IOException e) {
            String errorMessage = String.format("Failed to read Keycloak users: %s", e.getMessage());
            log.error(errorMessage);
            throw new RuntimeException(errorMessage,e);
        }
        if (users != null) {
            users.forEach(this::initKeycloakUser);
        }
    }

    private void initKeycloakUser(UserKeycloakDto user) {
        UserRepresentation userRepresentation = new UserRepresentation();
        userRepresentation.setEmail(user.getEmailId());
        userRepresentation.setUsername(user.getUserName());
        userRepresentation.setFirstName(user.getFirstName());
        userRepresentation.setLastName(user.getLastName());
        userRepresentation.setEnabled(true);
        userRepresentation.setEmailVerified(true);
        CredentialRepresentation userCredentialRepresentation = new CredentialRepresentation();
        userCredentialRepresentation.setType(CredentialRepresentation.PASSWORD);
        userCredentialRepresentation.setTemporary(false);
        userCredentialRepresentation.setValue(user.getPassword());
        userRepresentation.setCredentials(List.of(userCredentialRepresentation));

        try (Response response = keycloak.realm(REALM_ID).users().create(userRepresentation)) {
            String userId = null;
            if (response.getStatus() == 201) { // CREATED
                userId = CreatedResponseUtil.getCreatedId(response);
                log.info("User '{}' created with id {}", user.getUserName(), userId);
            } else if (response.getStatus() == 409) { // CONFLICT
                log.warn("User '{}' already exists. Fetching to assign roles if needed.", user.getUserName());
                List<UserRepresentation> users = keycloak.realm(REALM_ID).users().search(user.getUserName());
                if (!users.isEmpty()) {
                    userId = users.get(0).getId();
                } else {
                    log.error("User '{}' exists but could not be found by username for role assignment.", user.getUserName());
                    return;
                }
            } else {
                log.error("Failed to create user '{}'. Status: {}. Reason: {}", user.getUserName(), response.getStatus(), response.getStatusInfo().getReasonPhrase());
                return;
            }

            if (user.isAdmin() && userId != null) {
                UserResource userResource = keycloak.realm(REALM_ID).users().get(userId);
                List<RoleRepresentation> rolesToAdd =
                        Collections.singletonList(keycloak.realm(REALM_ID).roles().get("admin").toRepresentation());
                userResource.roles().realmLevel().add(rolesToAdd);
                log.info("Admin role assigned to user '{}'", user.getUserName());
            }
        }
    }

    public void reset() {
        try {
         keycloak.realm(REALM_ID).remove();
        } catch (NotFoundException e) {
            log.error("Failed to reset Keycloak", e);
        }
    }
}
