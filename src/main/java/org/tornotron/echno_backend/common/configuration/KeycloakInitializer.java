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
import org.keycloak.representations.idm.AuthenticationFlowRepresentation;
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
import java.util.HashSet;
import java.util.Collections;
import java.util.List;
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
            ensureAdminMfa();
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

    // Legacy realm role from the earlier custom-flow approach; now unused and cleaned up on reconcile.
    private static final String MFA_ROLE = "require-mfa";
    // Alias of the custom browser flow from the earlier approach; now deleted on reconcile.
    private static final String CUSTOM_MFA_FLOW_ALIAS = "echno-browser-mfa";
    // The standard built-in browser flow the realm is bound to.
    private static final String BUILTIN_BROWSER_FLOW = "browser";
    // Required action that forces TOTP enrolment at next login.
    private static final String CONFIGURE_TOTP = "CONFIGURE_TOTP";

    /**
     * Codifies admin-only MFA on every startup (both the fresh-realm and existing-realm paths),
     * fully idempotent and guarded so a failure only logs and never aborts startup.
     *
     * Admins are group members ('/org-{id}/system-admin'). MFA is enforced with the reliable
     * mechanism (proven live): the built-in 'browser' flow's "Browser - Conditional OTP" subflow
     * already prompts exactly the users who HAVE a TOTP credential, and the CONFIGURE_TOTP required
     * action forces enrolment. So each admin without OTP is given the CONFIGURE_TOTP required action
     * (forced enrolment on next login); once enrolled the built-in conditional-OTP prompts them
     * every login. Non-admins never receive CONFIGURE_TOTP, so they never hold OTP and are never
     * prompted, i.e. no MFA. New admins added later are picked up on the next deploy reconcile.
     */
    private void ensureAdminMfa() {
        try {
            log.info("Ensuring admin-only MFA (CONFIGURE_TOTP required action on the built-in browser flow)");
            useBuiltinBrowserFlow();
            enableConfigureTotpRequiredAction();
            forceTotpEnrolmentForAdmins();
            cleanupLegacyRequireMfaRole();
            log.info("Admin-only MFA reconcile complete");
        } catch (Exception e) {
            log.error("Failed to ensure admin MFA: {}", e.getMessage(), e);
        }
    }

    // (1) + (2) Bind the realm to the built-in 'browser' flow, then delete the leftover custom flow
    // (rebind first: a bound flow cannot be deleted). Each step guarded.
    private void useBuiltinBrowserFlow() {
        try {
            RealmRepresentation realm = keycloak.realm(REALM_ID).toRepresentation();
            if (!BUILTIN_BROWSER_FLOW.equals(realm.getBrowserFlow())) {
                realm.setBrowserFlow(BUILTIN_BROWSER_FLOW);
                keycloak.realm(REALM_ID).update(realm);
                log.info("Rebound realm browserFlow to the built-in '{}' flow", BUILTIN_BROWSER_FLOW);
            } else {
                log.info("Realm browserFlow already on the built-in '{}' flow", BUILTIN_BROWSER_FLOW);
            }
        } catch (Exception e) {
            log.error("Failed to rebind realm browserFlow to built-in '{}': {}", BUILTIN_BROWSER_FLOW, e.getMessage());
        }

        try {
            AuthenticationManagementResource flows = keycloak.realm(REALM_ID).flows();
            AuthenticationFlowRepresentation custom = flows.getFlows().stream()
                    .filter(f -> CUSTOM_MFA_FLOW_ALIAS.equals(f.getAlias()))
                    .findFirst()
                    .orElse(null);
            if (custom != null) {
                flows.deleteFlow(custom.getId());
                log.info("Deleted leftover custom MFA flow '{}' (id={})", CUSTOM_MFA_FLOW_ALIAS, custom.getId());
            }
        } catch (Exception e) {
            log.error("Failed to delete custom MFA flow '{}': {}", CUSTOM_MFA_FLOW_ALIAS, e.getMessage());
        }
    }

    // (4) Ensure the CONFIGURE_TOTP required action is enabled at realm level (defaultAction stays
    // false: only admins get it per-user, never every user globally).
    private void enableConfigureTotpRequiredAction() {
        try {
            RequiredActionProviderRepresentation action =
                    keycloak.realm(REALM_ID).flows().getRequiredAction(CONFIGURE_TOTP);
            if (action == null) {
                log.warn("{} required action not found; skipping enable", CONFIGURE_TOTP);
                return;
            }
            boolean changed = false;
            if (!action.isEnabled()) {
                action.setEnabled(true);
                changed = true;
            }
            if (action.isDefaultAction()) {
                action.setDefaultAction(false);
                changed = true;
            }
            if (changed) {
                keycloak.realm(REALM_ID).flows().updateRequiredAction(CONFIGURE_TOTP, action);
                log.info("Enabled {} required action (defaultAction=false)", CONFIGURE_TOTP);
            } else {
                log.info("{} required action already enabled", CONFIGURE_TOTP);
            }
        } catch (Exception e) {
            log.error("Failed to enable {} required action: {}", CONFIGURE_TOTP, e.getMessage());
        }
    }

    // (3) For each member of a '/system-admin' group who has no OTP credential and no pending
    // CONFIGURE_TOTP action, add CONFIGURE_TOTP to force enrolment on next login. Idempotent: never
    // touches an admin who already has OTP (would force needless re-enrolment) or already has the
    // action pending.
    private void forceTotpEnrolmentForAdmins() {
        try {
            List<GroupRepresentation> adminGroups = new ArrayList<>();
            collectSystemAdminGroups(keycloak.realm(REALM_ID).groups().groups(), adminGroups);
            if (adminGroups.isEmpty()) {
                log.info("No '/system-admin' groups found yet; no admins to enrol this reconcile");
                return;
            }

            Set<String> processed = new HashSet<>();
            for (GroupRepresentation group : adminGroups) {
                List<UserRepresentation> members = keycloak.realm(REALM_ID).groups().group(group.getId()).members();
                if (members == null) {
                    continue;
                }
                for (UserRepresentation member : members) {
                    if (member.getId() == null || !processed.add(member.getId())) {
                        continue;
                    }
                    forceTotpForAdmin(member.getId(), member.getUsername());
                }
            }
        } catch (Exception e) {
            log.error("Failed to force TOTP enrolment for admins: {}", e.getMessage());
        }
    }

    private void forceTotpForAdmin(String userId, String username) {
        try {
            UserResource userResource = keycloak.realm(REALM_ID).users().get(userId);

            boolean hasOtp = userResource.credentials().stream()
                    .map(CredentialRepresentation::getType)
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(t -> t.equalsIgnoreCase("otp")
                            || t.equalsIgnoreCase("totp")
                            || t.equalsIgnoreCase("hotp"));
            if (hasOtp) {
                log.info("Admin '{}' already has an OTP credential; not forcing enrolment", username);
                return;
            }

            UserRepresentation user = userResource.toRepresentation();
            List<String> requiredActions = user.getRequiredActions() == null
                    ? new ArrayList<>() : new ArrayList<>(user.getRequiredActions());
            if (requiredActions.contains(CONFIGURE_TOTP)) {
                log.info("Admin '{}' already has {} pending; leaving as-is", username, CONFIGURE_TOTP);
                return;
            }

            requiredActions.add(CONFIGURE_TOTP);
            user.setRequiredActions(requiredActions);
            userResource.update(user);
            log.info("Added {} required action to admin '{}' to force TOTP enrolment", CONFIGURE_TOTP, username);
        } catch (Exception e) {
            log.error("Failed to force TOTP for admin '{}': {}", username, e.getMessage());
        }
    }

    // (5) Clean up the abandoned custom-flow artifact: unmap the legacy 'require-mfa' role from the
    // '/system-admin' groups and delete the now-unused realm role (deleting the role also removes any
    // remaining direct user assignment). Best-effort; each step guarded, never fails startup.
    private void cleanupLegacyRequireMfaRole() {
        RoleRepresentation mfaRole;
        try {
            mfaRole = keycloak.realm(REALM_ID).roles().get(MFA_ROLE).toRepresentation();
        } catch (NotFoundException e) {
            return; // already gone
        } catch (Exception e) {
            log.error("Failed to read legacy role '{}': {}", MFA_ROLE, e.getMessage());
            return;
        }

        try {
            List<GroupRepresentation> adminGroups = new ArrayList<>();
            collectSystemAdminGroups(keycloak.realm(REALM_ID).groups().groups(), adminGroups);
            for (GroupRepresentation group : adminGroups) {
                try {
                    RoleMappingResource groupRoles = keycloak.realm(REALM_ID).groups().group(group.getId()).roles();
                    boolean mapped = groupRoles.realmLevel().listAll().stream()
                            .anyMatch(r -> MFA_ROLE.equals(r.getName()));
                    if (mapped) {
                        groupRoles.realmLevel().remove(List.of(mfaRole));
                        log.info("Unmapped legacy role '{}' from admin group '{}'", MFA_ROLE, group.getPath());
                    }
                } catch (Exception e) {
                    log.error("Failed to unmap legacy role '{}' from group '{}': {}", MFA_ROLE, group.getPath(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to enumerate groups for legacy role cleanup: {}", e.getMessage());
        }

        try {
            keycloak.realm(REALM_ID).roles().deleteRole(MFA_ROLE);
            log.info("Deleted unused legacy realm role '{}'", MFA_ROLE);
        } catch (Exception e) {
            log.error("Failed to delete legacy realm role '{}': {}", MFA_ROLE, e.getMessage());
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

    private void initKeycloak() {

        initKeycloakRealm();
        initKeycloakUsers();
        assignServiceAccountRoles();
        ensureAuthorizationSetup();
        ensureAdminMfa();

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
