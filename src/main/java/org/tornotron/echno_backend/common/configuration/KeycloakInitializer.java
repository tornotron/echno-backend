package org.tornotron.echno_backend.common.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.AuthenticationManagementResource;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.RoleMappingResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.AuthenticationExecutionInfoRepresentation;
import org.keycloak.representations.idm.AuthenticationFlowRepresentation;
import org.keycloak.representations.idm.AuthenticatorConfigRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RequiredActionProviderRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.idm.authorization.PolicyRepresentation;
import org.keycloak.representations.idm.authorization.ResourcePermissionRepresentation;
import org.keycloak.representations.idm.authorization.ResourceRepresentation;
import org.keycloak.representations.idm.authorization.RolePolicyRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.user.dto.UserKeycloakDto;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@DependsOn("keycloakConfigGenerator")
public class KeycloakInitializer {

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


    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        REALM_ID = keycloakInitializerConfigurationProperties.getApplicationRealm();

        if (keycloakInitializerConfigurationProperties.isInitializeOnStartup()) {
            log.info("Starting async Keycloak initialization...");
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
            // Sync realm-level security settings from JSON even if realm exists
            syncRealmSecuritySettings();
            // Ensure admin-only MFA (conditional TOTP) is codified even if realm exists
            ensureAdminMfaFlow();
            // Ensure service account roles are assigned even if realm exists
            assignServiceAccountRoles();
            // Ensure authorization setup (JS policy, resource, permission) exists
            ensureAuthorizationSetup();
        }
    }

    private void syncClientConfiguration() {
        try {
            log.info("Syncing configuration for all clients defined in init-keycloak.json");

            Path configFile = Paths.get(configOutput, "init-keycloak.json");
            if (!Files.exists(configFile)) {
                log.warn("Config file not found at: " + configFile + ". Skipping client sync.");
                return;
            }

            RealmRepresentation realmRep = mapper.readValue(configFile.toFile(), RealmRepresentation.class);
            List<org.keycloak.representations.idm.ClientRepresentation> clientsFromConfig = realmRep.getClients();
            if (clientsFromConfig == null || clientsFromConfig.isEmpty()) {
                log.warn("No clients found in configuration file. Skipping client sync.");
                return;
            }

            for (org.keycloak.representations.idm.ClientRepresentation clientFromConfig : clientsFromConfig) {
                syncSingleClient(clientFromConfig);
            }

        } catch (Exception e) {
            log.error("Failed to sync client configuration: {}", e.getMessage());
        }
    }

    private void syncSingleClient(org.keycloak.representations.idm.ClientRepresentation clientFromConfig) {
        String clientId = clientFromConfig.getClientId();
        try {
            List<org.keycloak.representations.idm.ClientRepresentation> existingClients = keycloak.realm(REALM_ID).clients().findByClientId(clientId);
            if (existingClients.isEmpty()) {
                log.warn("Client '{}' from config not found in Keycloak. Skipping sync.", clientId);
                return;
            }

            org.keycloak.representations.idm.ClientRepresentation existingClient = existingClients.get(0);
            org.keycloak.admin.client.resource.ClientResource clientResource = keycloak.realm(REALM_ID).clients().get(existingClient.getId());

            // Update fields from config on the existing client.
            // We preserve the ID and Secret from the existing client so a rotated secret survives.
            clientFromConfig.setId(existingClient.getId());
            clientFromConfig.setSecret(existingClient.getSecret());

            clientResource.update(clientFromConfig);
            log.info("Client '{}' configuration synced successfully.", clientId);

        } catch (Exception e) {
            log.error("Failed to sync client '{}': {}", clientId, e.getMessage());
        }
    }

    private void syncRealmSecuritySettings() {
        try {
            log.info("Syncing realm-level security settings for '{}'", REALM_ID);

            Path configFile = Paths.get(configOutput, "init-keycloak.json");
            if (!Files.exists(configFile)) {
                log.warn("Config file not found at: " + configFile + ". Skipping realm security sync.");
                return;
            }

            RealmRepresentation configRealm = mapper.readValue(configFile.toFile(), RealmRepresentation.class);
            RealmRepresentation liveRealm = keycloak.realm(REALM_ID).toRepresentation();

            // Copy ONLY the explicit security fields that are set in the config onto the live
            // representation. Clients, roles, groups and users are deliberately left untouched so
            // the seeded realm content is never clobbered.
            List<String> applied = new ArrayList<>();

            if (configRealm.getRevokeRefreshToken() != null) {
                liveRealm.setRevokeRefreshToken(configRealm.getRevokeRefreshToken());
                applied.add("revokeRefreshToken=" + configRealm.getRevokeRefreshToken());
            }
            if (configRealm.getRefreshTokenMaxReuse() != null) {
                liveRealm.setRefreshTokenMaxReuse(configRealm.getRefreshTokenMaxReuse());
                applied.add("refreshTokenMaxReuse=" + configRealm.getRefreshTokenMaxReuse());
            }
            if (configRealm.getSsoSessionIdleTimeout() != null) {
                liveRealm.setSsoSessionIdleTimeout(configRealm.getSsoSessionIdleTimeout());
                applied.add("ssoSessionIdleTimeout=" + configRealm.getSsoSessionIdleTimeout());
            }
            if (configRealm.getSsoSessionMaxLifespan() != null) {
                liveRealm.setSsoSessionMaxLifespan(configRealm.getSsoSessionMaxLifespan());
                applied.add("ssoSessionMaxLifespan=" + configRealm.getSsoSessionMaxLifespan());
            }

            if (applied.isEmpty()) {
                log.info("No realm-level security settings present in config; nothing to sync.");
                return;
            }

            keycloak.realm(REALM_ID).update(liveRealm);
            log.info("Realm-level security settings synced successfully: {}", applied);

        } catch (Exception e) {
            log.error("Failed to sync realm security settings: {}", e.getMessage());
        }
    }

    // Realm role that admin groups carry; the conditional browser flow keys MFA off this role.
    private static final String MFA_ROLE = "require-mfa";
    // Alias of the conditional browser flow we build by copying the built-in 'browser' flow.
    private static final String MFA_FLOW_ALIAS = "echno-browser-mfa";
    // Alias of the conditional subflow we add inside the copied flow's forms subflow.
    private static final String MFA_CONDITIONAL_SUBFLOW_ALIAS = "echno-admin-mfa-conditional";

    /**
     * Codifies admin-only MFA (conditional TOTP for admins only) and is safe to run on every
     * startup on both the fresh-realm and existing-realm paths. Admin membership is group-based
     * ('/org-{id}/system-admin'); since a Keycloak conditional flow can key off a realm role but
     * not a group, MFA is driven by the '{@value #MFA_ROLE}' realm role carried by admin groups.
     * Every step is check-before-create so re-runs never duplicate anything, and the whole method
     * is guarded so a failure logs but never aborts startup.
     */
    private void ensureAdminMfaFlow() {
        try {
            log.info("Ensuring admin-only MFA (conditional TOTP) configuration");
            ensureMfaRealmRole();
            mapMfaRoleOntoAdminGroups();
            enableConfigureTotpRequiredAction();
            ensureConditionalBrowserFlow();
            bindBrowserFlow();
            log.info("Admin-only MFA configuration complete");
        } catch (Exception e) {
            log.error("Failed to ensure admin MFA flow: {}", e.getMessage(), e);
        }
    }

    // (a) Create the '{@value #MFA_ROLE}' realm role if it is absent.
    private void ensureMfaRealmRole() {
        try {
            keycloak.realm(REALM_ID).roles().get(MFA_ROLE).toRepresentation();
            log.info("Realm role '{}' already exists", MFA_ROLE);
        } catch (NotFoundException e) {
            RoleRepresentation role = new RoleRepresentation();
            role.setName(MFA_ROLE);
            role.setDescription("Marker role: members are required to complete TOTP MFA at login");
            keycloak.realm(REALM_ID).roles().create(role);
            log.info("Created realm role '{}'", MFA_ROLE);
        }
    }

    // (b) Map the MFA role onto every '/system-admin' group. This re-runs each deploy, so any org
    // group created after this deploy is picked up on the next reconcile.
    private void mapMfaRoleOntoAdminGroups() {
        RoleRepresentation mfaRole = keycloak.realm(REALM_ID).roles().get(MFA_ROLE).toRepresentation();

        List<GroupRepresentation> adminGroups = new ArrayList<>();
        collectSystemAdminGroups(keycloak.realm(REALM_ID).groups().groups(), adminGroups);

        if (adminGroups.isEmpty()) {
            log.info("No '/system-admin' groups found yet; MFA role mapping will apply to future groups on the next reconcile");
            return;
        }

        for (GroupRepresentation group : adminGroups) {
            RoleMappingResource groupRoles = keycloak.realm(REALM_ID).groups().group(group.getId()).roles();
            boolean alreadyMapped = groupRoles.realmLevel().listAll().stream()
                    .anyMatch(r -> MFA_ROLE.equals(r.getName()));
            if (!alreadyMapped) {
                groupRoles.realmLevel().add(List.of(mfaRole));
                log.info("Mapped '{}' realm role onto admin group '{}'", MFA_ROLE, group.getPath());
            }
        }
    }

    // Recurse the whole group tree (subgroups included) collecting groups whose path ends with '/system-admin'.
    private void collectSystemAdminGroups(List<GroupRepresentation> groups, List<GroupRepresentation> out) {
        if (groups == null) {
            return;
        }
        for (GroupRepresentation group : groups) {
            if (group.getPath() != null && group.getPath().endsWith("/system-admin")) {
                out.add(group);
            }
            List<GroupRepresentation> subGroups =
                    keycloak.realm(REALM_ID).groups().group(group.getId()).getSubGroups(0, 1000, false);
            collectSystemAdminGroups(subGroups, out);
        }
    }

    // (c) Enable the CONFIGURE_TOTP required action so admins can enrol on first login. Enrolment is
    // driven by the flow (not globally), so defaultAction is left as-is.
    private void enableConfigureTotpRequiredAction() {
        try {
            RequiredActionProviderRepresentation action =
                    keycloak.realm(REALM_ID).flows().getRequiredAction("CONFIGURE_TOTP");
            if (action == null) {
                log.warn("CONFIGURE_TOTP required action not found; skipping enable");
                return;
            }
            if (!action.isEnabled()) {
                action.setEnabled(true);
                keycloak.realm(REALM_ID).flows().updateRequiredAction("CONFIGURE_TOTP", action);
                log.info("Enabled CONFIGURE_TOTP required action");
            } else {
                log.info("CONFIGURE_TOTP required action already enabled");
            }
        } catch (Exception e) {
            log.error("Failed to enable CONFIGURE_TOTP required action: {}", e.getMessage());
        }
    }

    // (d) Build the conditional browser flow by copying the built-in 'browser' flow (a known-good
    // base, so a non-admin login stays identical to today) and adding a single conditional subflow.
    private void ensureConditionalBrowserFlow() {
        AuthenticationManagementResource flows = keycloak.realm(REALM_ID).flows();

        boolean flowExists = flows.getFlows().stream()
                .anyMatch(f -> MFA_FLOW_ALIAS.equals(f.getAlias()));
        if (flowExists) {
            log.info("Browser MFA flow '{}' already exists; leaving it unchanged", MFA_FLOW_ALIAS);
            return;
        }

        // Copy the built-in 'browser' flow to our own alias.
        Map<String, Object> copyData = new HashMap<>();
        copyData.put("newName", MFA_FLOW_ALIAS);
        try (Response response = flows.copy("browser", copyData)) {
            if (response.getStatus() != 201) {
                log.error("Failed to copy 'browser' flow to '{}'. Status: {}", MFA_FLOW_ALIAS, response.getStatus());
                return;
            }
        }
        log.info("Created browser MFA flow '{}' by copying the built-in 'browser' flow", MFA_FLOW_ALIAS);

        // Locate the copied flow's forms subflow (a direct child subflow, alias contains 'forms').
        List<AuthenticationExecutionInfoRepresentation> executions = flows.getExecutions(MFA_FLOW_ALIAS);
        AuthenticationExecutionInfoRepresentation formsSubflow = executions.stream()
                .filter(e -> Boolean.TRUE.equals(e.getAuthenticationFlow()))
                .filter(e -> e.getLevel() == 0)
                .filter(e -> e.getAlias() != null && e.getAlias().toLowerCase().contains("forms"))
                .findFirst()
                .orElseGet(() -> executions.stream()
                        .filter(e -> Boolean.TRUE.equals(e.getAuthenticationFlow()))
                        .filter(e -> e.getLevel() == 0)
                        .findFirst()
                        .orElse(null));

        if (formsSubflow == null || formsSubflow.getAlias() == null) {
            log.error("Could not locate the forms subflow in '{}'; leaving flow unbound", MFA_FLOW_ALIAS);
            return;
        }
        String formsAlias = formsSubflow.getAlias();

        // Add a conditional subflow inside the forms subflow.
        Map<String, Object> subflowData = new HashMap<>();
        subflowData.put("alias", MFA_CONDITIONAL_SUBFLOW_ALIAS);
        subflowData.put("type", "basic-flow");
        subflowData.put("description", "Require TOTP for users carrying the require-mfa role (admins)");
        subflowData.put("provider", "registration-page-form");
        flows.addExecutionFlow(formsAlias, subflowData);

        // Add, in order, the role condition then the OTP form inside the conditional subflow.
        flows.addExecution(MFA_CONDITIONAL_SUBFLOW_ALIAS, Map.of("provider", "conditional-user-role"));
        flows.addExecution(MFA_CONDITIONAL_SUBFLOW_ALIAS, Map.of("provider", "auth-otp-form"));

        // Set requirements and the role-condition config, scoping strictly to our subflow's children.
        List<AuthenticationExecutionInfoRepresentation> afterAdd = flows.getExecutions(MFA_FLOW_ALIAS);

        int subflowIndex = -1;
        AuthenticationExecutionInfoRepresentation conditionalSubflow = null;
        for (int i = 0; i < afterAdd.size(); i++) {
            AuthenticationExecutionInfoRepresentation e = afterAdd.get(i);
            if (Boolean.TRUE.equals(e.getAuthenticationFlow())
                    && MFA_CONDITIONAL_SUBFLOW_ALIAS.equals(e.getAlias())) {
                subflowIndex = i;
                conditionalSubflow = e;
                break;
            }
        }
        if (conditionalSubflow == null) {
            log.error("Could not find the conditional subflow after creation in '{}'; leaving flow unbound", MFA_FLOW_ALIAS);
            return;
        }

        conditionalSubflow.setRequirement("CONDITIONAL");
        flows.updateExecutions(MFA_FLOW_ALIAS, conditionalSubflow);

        int parentLevel = conditionalSubflow.getLevel();
        for (int i = subflowIndex + 1; i < afterAdd.size(); i++) {
            AuthenticationExecutionInfoRepresentation child = afterAdd.get(i);
            if (child.getLevel() <= parentLevel) {
                break; // left the conditional subflow's descendants
            }
            if ("conditional-user-role".equals(child.getProviderId())) {
                child.setRequirement("REQUIRED");
                flows.updateExecutions(MFA_FLOW_ALIAS, child);

                AuthenticatorConfigRepresentation roleConfig = new AuthenticatorConfigRepresentation();
                roleConfig.setAlias("echno-require-mfa-role-condition");
                Map<String, String> config = new HashMap<>();
                config.put("role", MFA_ROLE);
                config.put("negate", "false");
                roleConfig.setConfig(config);
                flows.newExecutionConfig(child.getId(), roleConfig);
            } else if ("auth-otp-form".equals(child.getProviderId())) {
                child.setRequirement("REQUIRED");
                flows.updateExecutions(MFA_FLOW_ALIAS, child);
            }
        }

        log.info("Built conditional subflow '{}' (role condition '{}' -> OTP Form) inside '{}'",
                MFA_CONDITIONAL_SUBFLOW_ALIAS, MFA_ROLE, MFA_FLOW_ALIAS);
    }

    // (e) Bind the realm's browser flow to our conditional flow, only if not already bound.
    private void bindBrowserFlow() {
        // Only bind if the flow was actually built.
        boolean flowExists = keycloak.realm(REALM_ID).flows().getFlows().stream()
                .anyMatch(f -> MFA_FLOW_ALIAS.equals(f.getAlias()));
        if (!flowExists) {
            log.warn("Browser MFA flow '{}' does not exist; not binding browserFlow", MFA_FLOW_ALIAS);
            return;
        }

        RealmRepresentation realm = keycloak.realm(REALM_ID).toRepresentation();
        if (MFA_FLOW_ALIAS.equals(realm.getBrowserFlow())) {
            log.info("Realm browserFlow already bound to '{}'", MFA_FLOW_ALIAS);
            return;
        }
        realm.setBrowserFlow(MFA_FLOW_ALIAS);
        keycloak.realm(REALM_ID).update(realm);
        log.info("Bound realm browserFlow to '{}' (admin-only MFA active)", MFA_FLOW_ALIAS);
    }

    private void initKeycloak() {

        initKeycloakRealm();
        initKeycloakUsers();
        assignServiceAccountRoles();
        ensureAuthorizationSetup();
        ensureAdminMfaFlow();

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

            if (userId != null) {
                UserResource userResource = keycloak.realm(REALM_ID).users().get(userId);
                String roleName = user.isAdmin() ? "admin" : "user";
                List<RoleRepresentation> rolesToAdd =
                        Collections.singletonList(keycloak.realm(REALM_ID).roles().get(roleName).toRepresentation());
                userResource.roles().realmLevel().add(rolesToAdd);
                log.info("Role '{}' assigned to user '{}'", roleName, user.getUserName());
            }
        }
    }

    private void ensureAuthorizationSetup() {
        try {
            log.info("Ensuring authorization setup for client '{}'", appClientId);

            List<org.keycloak.representations.idm.ClientRepresentation> clients =
                    keycloak.realm(REALM_ID).clients().findByClientId(appClientId);
            if (clients.isEmpty()) {
                log.warn("Client '{}' not found, skipping authorization setup", appClientId);
                return;
            }

            ClientResource clientResource = keycloak.realm(REALM_ID).clients().get(clients.get(0).getId());

            String policyId = ensureDefaultRolePolicy(clientResource);
            String resourceId = ensureDefaultResource(clientResource);
            ensureDefaultPermission(clientResource, policyId, resourceId);
            ensureUsersHaveBaseRole();

            log.info("Authorization setup complete for client '{}'", appClientId);
        } catch (Exception e) {
            log.error("Failed to ensure authorization setup: {}", e.getMessage(), e);
        }
    }

    // Returns the policy ID (existing or newly created)
    private String ensureDefaultRolePolicy(ClientResource clientResource) {
        String policyName = "Default Policy";
        List<PolicyRepresentation> existing = clientResource.authorization().policies().policies();

        PolicyRepresentation existingPolicy = existing == null ? null : existing.stream()
                .filter(p -> policyName.equals(p.getName()))
                .findFirst().orElse(null);

        if (existingPolicy != null) {
            log.info("Policy '{}' already exists (id={}), skipping", policyName, existingPolicy.getId());
            return existingPolicy.getId();
        }

        RolePolicyRepresentation policy = new RolePolicyRepresentation();
        policy.setName(policyName);
        policy.setDescription("A policy that grants access to all authenticated users.");

        // required=false = OR logic — user needs at least one of these roles
        try {
            RoleRepresentation userRole = keycloak.realm(REALM_ID).roles().get("user").toRepresentation();
            policy.addRole(userRole.getId(), false);
        } catch (Exception e) {
            log.warn("Realm role 'user' not found, skipping from Default Policy");
        }
        try {
            RoleRepresentation adminRole = keycloak.realm(REALM_ID).roles().get("admin").toRepresentation();
            policy.addRole(adminRole.getId(), false);
        } catch (Exception e) {
            log.warn("Realm role 'admin' not found, skipping from Default Policy");
        }

        log.info("Attempting to create role-based Default Policy");
        try (Response response = clientResource.authorization().policies().role().create(policy)) {
            String body = response.hasEntity() ? response.readEntity(String.class) : "(no body)";
            if (response.getStatus() == 201) {
                String id = extractCreatedId(response, body);
                if (id == null) {
                    // Fall back to looking the policy up by name
                    List<PolicyRepresentation> policies = clientResource.authorization().policies().policies();
                    id = policies == null ? null : policies.stream()
                            .filter(p -> policyName.equals(p.getName()))
                            .map(PolicyRepresentation::getId)
                            .findFirst().orElse(null);
                }
                log.info("Default Policy created successfully (id={})", id);
                return id;
            } else {
                log.error("Failed to create Default Policy. Status: {}, Body: {}", response.getStatus(), body);
                return null;
            }
        }
    }

    /**
     * Keycloak's authorization endpoints answer a 201 with the created representation in the body and
     * no Location header, unlike the users/clients endpoints. Handle both shapes.
     */
    private String extractCreatedId(Response response, String body) {
        String location = response.getHeaderString("Location");
        if (location != null && !location.isBlank()) {
            return location.substring(location.lastIndexOf('/') + 1);
        }

        if (body == null || body.isBlank()) {
            return null;
        }

        try {
            var node = mapper.readTree(body);
            // resources are keyed by '_id', policies and permissions by 'id'
            var id = node.hasNonNull("_id") ? node.get("_id") : node.get("id");
            return id == null || id.isNull() ? null : id.asText();
        } catch (IOException e) {
            log.warn("Could not parse created id from response body: {}", e.getMessage());
            return null;
        }
    }

    // Returns the resource ID (existing or newly created)
    private String ensureDefaultResource(ClientResource clientResource) {
        String resourceName = "Default Resource";
        var existingResource = clientResource.authorization().resources().resources().stream()
                .filter(r -> resourceName.equals(r.getName()))
                .findFirst().orElse(null);

        if (existingResource != null) {
            log.info("Resource '{}' already exists (id={}), skipping", resourceName, existingResource.getId());
            return existingResource.getId();
        }

        ResourceRepresentation resource = new ResourceRepresentation();
        resource.setName(resourceName);
        resource.setUris(Set.of("/*"));

        try (Response response = clientResource.authorization().resources().create(resource)) {
            String body = response.hasEntity() ? response.readEntity(String.class) : "(no body)";
            if (response.getStatus() == 201) {
                String id = extractCreatedId(response, body);
                if (id == null) {
                    // Fall back to looking the resource up by name
                    id = clientResource.authorization().resources().resources().stream()
                            .filter(r -> resourceName.equals(r.getName()))
                            .map(ResourceRepresentation::getId)
                            .findFirst().orElse(null);
                }
                log.info("Resource '{}' created successfully (id={})", resourceName, id);
                return id;
            } else {
                log.error("Failed to create resource '{}'. Status: {}, Body: {}", resourceName, response.getStatus(), body);
                return null;
            }
        }
    }

    private void ensureDefaultPermission(ClientResource clientResource, String policyId, String resourceId) {
        String permissionName = "Default Permission";

        if (policyId == null || resourceId == null) {
            log.error("Cannot create/update permission: policyId={}, resourceId={}", policyId, resourceId);
            return;
        }

        List<PolicyRepresentation> existing = clientResource.authorization().policies().policies();
        PolicyRepresentation existingPermission = existing == null ? null : existing.stream()
                .filter(p -> permissionName.equals(p.getName()))
                .findFirst().orElse(null);

        ResourcePermissionRepresentation permission = new ResourcePermissionRepresentation();
        permission.setName(permissionName);
        permission.setDescription("A permission that applies to the default resource type");
        permission.setPolicies(Set.of(policyId));
        permission.setResources(Set.of(resourceId));

        if (existingPermission != null) {
            // Always update to ensure the policy ID is correctly linked
            permission.setId(existingPermission.getId());
            clientResource.authorization().permissions().resource()
                    .findById(existingPermission.getId()).update(permission);
            log.info("Permission '{}' updated with correct policy and resource link", permissionName);
        } else {
            try (Response response = clientResource.authorization().permissions().resource().create(permission)) {
                String body = response.hasEntity() ? response.readEntity(String.class) : "(no body)";
                if (response.getStatus() == 201) {
                    log.info("Permission '{}' created successfully", permissionName);
                } else {
                    log.error("Failed to create permission '{}'. Status: {}, Body: {}", permissionName, response.getStatus(), body);
                }
            }
        }
    }

    private void ensureUsersHaveBaseRole() {
        try {
            log.info("Ensuring all users have at least the 'user' realm role");
            RoleRepresentation userRole = keycloak.realm(REALM_ID).roles().get("user").toRepresentation();

            List<UserRepresentation> allUsers = keycloak.realm(REALM_ID).users().list();
            for (UserRepresentation u : allUsers) {
                List<RoleRepresentation> realmRoles = keycloak.realm(REALM_ID).users()
                        .get(u.getId()).roles().realmLevel().listAll();
                boolean hasRole = realmRoles.stream()
                        .anyMatch(r -> "user".equals(r.getName()) || "admin".equals(r.getName()));
                if (!hasRole) {
                    keycloak.realm(REALM_ID).users().get(u.getId()).roles()
                            .realmLevel().add(Collections.singletonList(userRole));
                    log.info("Assigned 'user' role to existing user '{}'", u.getUsername());
                }
            }
            log.info("Base role check complete for {} users", allUsers.size());
        } catch (Exception e) {
            log.error("Failed to ensure base role for users: {}", e.getMessage(), e);
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
