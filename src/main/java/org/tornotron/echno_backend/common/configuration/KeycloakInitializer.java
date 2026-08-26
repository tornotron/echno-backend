package org.tornotron.echno_backend.common.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.AuthenticationManagementResource;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleMappingResource;
import org.keycloak.admin.client.resource.RoleResource;
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

    // Bootstrap-only master client (admin-cli password grant on the master realm). Used solely to
    // create the application realm and to provision/repair the echno-initializer client on a fresh
    // or not-yet-migrated environment. Never used for steady-state reconcile once the scoped client
    // is usable.
    private final Keycloak masterKeycloak;

    // The admin client every reconcile/content operation runs through. It is set to the realm-scoped
    // echno-initializer client for the whole reconcile; the master client is only reached, if at all,
    // to bootstrap that scoped client into existence, or as a last-resort fallback when the scoped
    // client cannot authenticate even after repair.
    private Keycloak admin;

    private final KeycloakConfig keycloakConfig;

    private final KeycloakInitializerConfigurationProperties keycloakInitializerConfigurationProperties;

    private final ObjectMapper mapper;

    private static String REALM_ID;

    // Composite role on the realm-management client that grants full admin over a single realm.
    private static final String REALM_ADMIN_ROLE = "realm-admin";
    private static final String REALM_MANAGEMENT_CLIENT = "realm-management";

    @Value("${keycloak.config.output-path}")
    private String configOutput;

    @Value("${keycloak.client-id}")
    private String appClientId;

    public KeycloakInitializer(Keycloak masterKeycloak,
                               KeycloakInitializerConfigurationProperties keycloakInitializerConfigurationProperties,
                               ObjectMapper objectMapper,
                               KeycloakConfig keycloakConfig) {
        this.masterKeycloak = masterKeycloak;
        this.keycloakInitializerConfigurationProperties = keycloakInitializerConfigurationProperties;
        this.mapper = objectMapper;
        this.keycloakConfig = keycloakConfig;
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

    /**
     * Reconciles Keycloak on startup, authenticating with the least-privileged credential that can
     * do the job (H-3). The scoped realm-admin path is tried first: if the echno-initializer service
     * account can already obtain a token against the application realm, the whole reconcile runs
     * through it and the privileged master credential is never touched this startup. The master
     * client is used only to bootstrap a fresh (or not-yet-migrated) environment up to the point
     * where that scoped client exists: create the realm and provision echno-initializer with the
     * realm-admin role, then hand off to the scoped client for all remaining work.
     */
    public void init(boolean overwrite) {

        log.info("Initializer start");

        Keycloak scoped = keycloakConfig.buildScopedKeycloak();
        try {
            if (!overwrite && probeRealm(scoped)) {
                log.info("Scoped initializer client authenticated against realm '{}'; reconciling with the "
                        + "realm-scoped admin only (master credential untouched)", REALM_ID);
                this.admin = scoped;
                reconcileExistingRealm();
                log.info("Keycloak reconcile complete (scoped realm-admin)");
                return;
            }
        } finally {
            if (this.admin != scoped) {
                closeQuietly(scoped);
            }
        }

        // Fresh env, not-yet-migrated realm, an unusable scoped client, or an explicit overwrite:
        // fall back to the master credential to bring the scoped client into existence, then hand
        // reconcile off to the scoped client (or to master if the scoped client still cannot log in).
        bootstrapWithMaster(overwrite);
    }

    /**
     * Bootstrap/repair path. Uses the master credential ONLY to create the realm (if absent) and to
     * provision or repair the echno-initializer client with realm-admin. Every subsequent operation
     * runs through the freshly built scoped client once it is proven to authenticate; if it still
     * cannot (misconfigured secret, etc.) the whole reconcile falls back to master for this startup
     * rather than being left half-done through a non-authenticating client.
     */
    private void bootstrapWithMaster(boolean overwrite) {
        boolean realmExists = probeRealm(masterKeycloak);

        if (realmExists && overwrite) {
            reset();
            realmExists = false;
        }

        if (!realmExists) {
            initKeycloakRealm();
        }

        // Ensure the realm-scoped admin client exists with a secret Keycloak actually validates (the
        // create path honours ClientRepresentation.setSecret; the realign path deletes and recreates
        // it, never an in-place secret update) and holds realm-admin. This is the one step that
        // genuinely needs the master credential on a fresh or pre-migration environment, because the
        // scoped client cannot yet authenticate to create itself.
        ensureInitializerClient();

        Keycloak scoped = keycloakConfig.buildScopedKeycloak();
        boolean scopedUsable = verifyScoped(scoped);

        try {
            if (scopedUsable) {
                this.admin = scoped;
                if (!realmExists) {
                    initKeycloakContents();
                    log.info("Keycloak initialized successfully (fresh bootstrap; scoped realm-admin provisioned)");
                } else {
                    log.warn("Realm '{}' already existed but the scoped initializer client was not usable; "
                            + "repaired it via master and reconciled through the scoped realm-admin", REALM_ID);
                    reconcileExistingRealm();
                    log.info("Keycloak reconcile complete (scoped realm-admin, provisioned this startup)");
                }
            } else {
                // The scoped client still cannot obtain a token even after repair. Do NOT run a
                // half-broken reconcile through it (that is exactly the 401 spam we are avoiding);
                // complete this startup through the master credential and warn loudly.
                closeQuietly(scoped);
                this.admin = masterKeycloak;
                log.warn("Scoped initializer client '{}' could not authenticate against realm '{}' even after "
                                + "provisioning; falling back to the master credential for this startup's reconcile. "
                                + "Check keycloak.initializer.service-client-secret.",
                        keycloakConfig.getInitializerServiceClientId(), REALM_ID);
                if (!realmExists) {
                    initKeycloakContents();
                    log.info("Keycloak initialized successfully (fresh bootstrap; master fallback)");
                } else {
                    reconcileExistingRealm();
                    log.info("Keycloak reconcile complete (master fallback)");
                }
            }
        } finally {
            if (this.admin != scoped) {
                closeQuietly(scoped);
            }
        }
    }

    /** The existing-realm reconcile suite, run through {@link #admin}. */
    private void reconcileExistingRealm() {
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
        // Ensure the H-1 composite job roles exist and carry their granular children
        ensureCompositeJobRoles();
    }

    /**
     * Probes whether the given admin client can read the application realm. A success proves both
     * that the realm exists and that the client authenticated. Any failure (realm absent, client
     * absent, or token request rejected) returns false so the caller falls back to bootstrap.
     */
    private boolean probeRealm(Keycloak client) {
        try {
            client.realm(REALM_ID).toRepresentation();
            return true;
        } catch (NotFoundException e) {
            return false;
        } catch (Exception e) {
            log.debug("Realm probe against '{}' did not succeed: {}", REALM_ID, e.getMessage());
            return false;
        }
    }

    /**
     * The real client-credentials check that the reverted attempt never exercised: obtain an access
     * token as the scoped service account and confirm it can read the realm. This is exactly the
     * grant that returned unauthorized_client live, so we run it before trusting the scoped client.
     */
    private boolean verifyScoped(Keycloak scoped) {
        try {
            String token = scoped.tokenManager().getAccessTokenString();
            if (token == null || token.isBlank()) {
                return false;
            }
            // A token alone proves the client_credentials grant; the read proves realm-admin authority.
            scoped.realm(REALM_ID).toRepresentation();
            return true;
        } catch (Exception e) {
            log.warn("Scoped initializer client failed client_credentials verification against realm '{}': {}",
                    REALM_ID, e.getMessage());
            return false;
        }
    }

    private void closeQuietly(Keycloak client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (Exception e) {
            log.debug("Failed to close a Keycloak admin client: {}", e.getMessage());
        }
    }

    /**
     * Ensures the realm-scoped initializer client (echno-initializer) exists in the application
     * realm as a confidential client whose stored secret equals the configured one and whose service
     * account holds the realm-admin composite.
     *
     * <p>The critical detail (the reason the reverted attempt failed live): Keycloak only honours a
     * caller-supplied secret in {@link org.keycloak.representations.idm.ClientRepresentation#setSecret}
     * on CREATE. Setting it on an existing client and calling update does NOT replace the credential
     * Keycloak validates, so a subsequent client_credentials grant is rejected as unauthorized_client.
     * Therefore:
     * <ul>
     *   <li>MISSING client: create it with the secret in the representation.</li>
     *   <li>EXISTING but unusable client (we only reach here when the scoped probe already failed):
     *       DELETE it and recreate with the configured secret, never an in-place secret update. This
     *       guarantees the stored credential equals the config value.</li>
     * </ul>
     * Runs through the master client (the only credential able to create it on a fresh or
     * pre-migration environment) and is idempotent.
     */
    private void ensureInitializerClient() {
        String clientId = keycloakConfig.getInitializerServiceClientId();
        String secret = keycloakConfig.getInitializerServiceClientSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("No keycloak.initializer.service-client-secret configured; the scoped initializer client "
                    + "cannot be given a usable secret and reconcile will fall back to the master credential.");
        }
        try {
            RealmResource realm = masterKeycloak.realm(REALM_ID);

            List<org.keycloak.representations.idm.ClientRepresentation> existing =
                    realm.clients().findByClientId(clientId);

            // Realign case: the client already exists but could not authenticate (we only ever run
            // ensureInitializerClient after the scoped probe failed). An in-place secret update does
            // not establish the validated credential, so delete and recreate from scratch.
            if (!existing.isEmpty()) {
                String staleUuid = existing.get(0).getId();
                realm.clients().get(staleUuid).remove();
                log.warn("Deleted existing initializer client '{}' (id={}) to recreate it with a validated "
                        + "secret (in-place secret updates are not honoured by Keycloak)", clientId, staleUuid);
            }

            String clientUuid = createInitializerClient(realm, clientId, secret);
            if (clientUuid == null) {
                return;
            }
            grantRealmAdmin(realm, clientUuid, clientId);

        } catch (Exception e) {
            log.error("Failed to ensure initializer client '{}': {}", clientId, e.getMessage(), e);
        }
    }

    /**
     * Creates the confidential echno-initializer service-account client with the configured secret
     * in the representation (the only reliable way to set a specific, Keycloak-validated secret).
     * Returns the created client UUID, or null on failure.
     */
    private String createInitializerClient(RealmResource realm, String clientId, String secret) {
        org.keycloak.representations.idm.ClientRepresentation rep =
                new org.keycloak.representations.idm.ClientRepresentation();
        rep.setClientId(clientId);
        rep.setEnabled(true);
        rep.setPublicClient(false);
        rep.setServiceAccountsEnabled(true);
        rep.setStandardFlowEnabled(false);
        rep.setDirectAccessGrantsEnabled(false);
        rep.setDescription("Realm-scoped admin client used by the startup initializer (H-3). "
                + "Holds realm-admin on this realm only; steady-state deploys authenticate as this "
                + "service account instead of the master credential.");
        if (secret != null && !secret.isBlank()) {
            rep.setSecret(secret);
        }
        try (Response response = realm.clients().create(rep)) {
            if (response.getStatus() != 201) {
                String body = response.hasEntity() ? response.readEntity(String.class) : "(no body)";
                log.error("Failed to create initializer client '{}'. Status: {}, Body: {}",
                        clientId, response.getStatus(), body);
                return null;
            }
            String clientUuid = CreatedResponseUtil.getCreatedId(response);
            log.info("Created realm-scoped initializer client '{}' (id={}) with a validated secret",
                    clientId, clientUuid);
            return clientUuid;
        }
    }

    /** Grants the realm-admin composite (from realm-management) to the client's service account, if missing. */
    private void grantRealmAdmin(RealmResource realm, String clientUuid, String clientId) {
        try {
            UserRepresentation serviceAccount = realm.clients().get(clientUuid).getServiceAccountUser();
            if (serviceAccount == null) {
                log.error("Initializer client '{}' has no service account user; cannot grant {}",
                        clientId, REALM_ADMIN_ROLE);
                return;
            }

            List<org.keycloak.representations.idm.ClientRepresentation> realmMgmt =
                    realm.clients().findByClientId(REALM_MANAGEMENT_CLIENT);
            if (realmMgmt.isEmpty()) {
                log.error("'{}' client not found; cannot grant {} to '{}'",
                        REALM_MANAGEMENT_CLIENT, REALM_ADMIN_ROLE, clientId);
                return;
            }
            String realmMgmtUuid = realmMgmt.get(0).getId();

            RoleRepresentation realmAdmin = realm.clients().get(realmMgmtUuid)
                    .roles().get(REALM_ADMIN_ROLE).toRepresentation();

            RoleMappingResource saRoles = realm.users().get(serviceAccount.getId()).roles();
            boolean alreadyAssigned = saRoles.clientLevel(realmMgmtUuid).listAll().stream()
                    .anyMatch(r -> REALM_ADMIN_ROLE.equals(r.getName()));

            if (alreadyAssigned) {
                log.info("Initializer service account for '{}' already holds {}", clientId, REALM_ADMIN_ROLE);
                return;
            }

            saRoles.clientLevel(realmMgmtUuid).add(List.of(realmAdmin));
            log.info("Granted {} to the initializer service account for '{}'", REALM_ADMIN_ROLE, clientId);
        } catch (Exception e) {
            log.error("Failed to grant {} to initializer client '{}': {}",
                    REALM_ADMIN_ROLE, clientId, e.getMessage(), e);
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
            List<org.keycloak.representations.idm.ClientRepresentation> existingClients = admin.realm(REALM_ID).clients().findByClientId(clientId);
            if (existingClients.isEmpty()) {
                log.warn("Client '{}' from config not found in Keycloak. Skipping sync.", clientId);
                return;
            }

            org.keycloak.representations.idm.ClientRepresentation existingClient = existingClients.get(0);
            org.keycloak.admin.client.resource.ClientResource clientResource = admin.realm(REALM_ID).clients().get(existingClient.getId());

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
            RealmRepresentation liveRealm = admin.realm(REALM_ID).toRepresentation();

            // Copy ONLY the explicit security fields that are set in the config onto the live
            // representation. Clients, roles, groups and users are deliberately left untouched so
            // the seeded realm content is never clobbered.
            List<String> applied = new ArrayList<>();

            // Self-service registration is the one realm security setting we deliberately let an
            // instance flip: default false keeps dedicated/per-client instances closed (the hardened
            // posture), and the public shared instance sets keycloak.registration-allowed=true so a
            // signed-up user can create their own org. The template always emits the value, so it is
            // reconciled onto the realm on every deploy. No other security setting is relaxed here.
            if (configRealm.isRegistrationAllowed() != null) {
                liveRealm.setRegistrationAllowed(configRealm.isRegistrationAllowed());
                applied.add("registrationAllowed=" + configRealm.isRegistrationAllowed());
            }

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

            admin.realm(REALM_ID).update(liveRealm);
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
            RealmRepresentation realm = admin.realm(REALM_ID).toRepresentation();
            if (!BUILTIN_BROWSER_FLOW.equals(realm.getBrowserFlow())) {
                realm.setBrowserFlow(BUILTIN_BROWSER_FLOW);
                admin.realm(REALM_ID).update(realm);
                log.info("Rebound realm browserFlow to the built-in '{}' flow", BUILTIN_BROWSER_FLOW);
            } else {
                log.info("Realm browserFlow already on the built-in '{}' flow", BUILTIN_BROWSER_FLOW);
            }
        } catch (Exception e) {
            log.error("Failed to rebind realm browserFlow to built-in '{}': {}", BUILTIN_BROWSER_FLOW, e.getMessage());
        }

        try {
            AuthenticationManagementResource flows = admin.realm(REALM_ID).flows();
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
                    admin.realm(REALM_ID).flows().getRequiredAction(CONFIGURE_TOTP);
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
                admin.realm(REALM_ID).flows().updateRequiredAction(CONFIGURE_TOTP, action);
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
            collectSystemAdminGroups(admin.realm(REALM_ID).groups().groups(), adminGroups);
            if (adminGroups.isEmpty()) {
                log.info("No '/system-admin' groups found yet; no admins to enrol this reconcile");
                return;
            }

            Set<String> processed = new HashSet<>();
            for (GroupRepresentation group : adminGroups) {
                List<UserRepresentation> members = admin.realm(REALM_ID).groups().group(group.getId()).members();
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
            UserResource userResource = admin.realm(REALM_ID).users().get(userId);

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
            mfaRole = admin.realm(REALM_ID).roles().get(MFA_ROLE).toRepresentation();
        } catch (NotFoundException e) {
            return; // already gone
        } catch (Exception e) {
            log.error("Failed to read legacy role '{}': {}", MFA_ROLE, e.getMessage());
            return;
        }

        try {
            List<GroupRepresentation> adminGroups = new ArrayList<>();
            collectSystemAdminGroups(admin.realm(REALM_ID).groups().groups(), adminGroups);
            for (GroupRepresentation group : adminGroups) {
                try {
                    RoleMappingResource groupRoles = admin.realm(REALM_ID).groups().group(group.getId()).roles();
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
            admin.realm(REALM_ID).roles().deleteRole(MFA_ROLE);
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
                    admin.realm(REALM_ID).groups().group(group.getId()).getSubGroups(0, 1000, false);
            collectSystemAdminGroups(subGroups, out);
        }
    }

    /**
     * The fresh-realm content pipeline, run through {@link #admin}. Realm creation itself is handled
     * separately in {@link #bootstrapWithMaster} because it is the one step that needs the master
     * credential.
     */
    private void initKeycloakContents() {
        initKeycloakUsers();
        assignServiceAccountRoles();
        ensureAuthorizationSetup();
        ensureAdminMfa();
        ensureCompositeJobRoles();
    }

    /**
     * Immutable definition of one composite job role: a coarse, function-based realm role that
     * bundles a set of the fine-grained occupation realm roles as its composite children.
     */
    private static final class CompositeJobRole {
        final String name;
        final String description;
        final List<String> children;

        CompositeJobRole(String name, String description, List<String> children) {
            this.name = name;
            this.description = description;
            this.children = children;
        }
    }

    /**
     * H-1 (RBAC role-model improvement): the realm ships ~52 fine-grained occupation realm roles
     * (mason, site-engineer, accountant, ...) that today have to be assigned one by one. These
     * composite job roles group them by function so an org admin can grant a single job role and
     * the member inherits every child occupation role through Keycloak's composite-role evaluation.
     *
     * Purely additive: the granular occupation roles are left exactly as they are, no user or group
     * is auto-assigned a composite here, and the org-scoped role subgroups (system-admin, org-manager,
     * hr-admin, project-manager, resolved from the JWT "groups" claim by JwtAuthConverter) are a
     * separate authorization layer that this change does not touch. The occupation role 'system-admin'
     * is deliberately left out of every family: it is the administrative role driven through the
     * org-scoped subgroup layer, not a job title to be bundled.
     */
    private static final List<CompositeJobRole> COMPOSITE_JOB_ROLES = List.of(
            new CompositeJobRole("job-site-management",
                    "Composite job role: site leadership, supervision and safety.",
                    List.of("site-manager", "site-supervisor", "site-engineer", "foreman",
                            "supervisor", "safety-officer", "technical-coordinator", "document-controller")),
            new CompositeJobRole("job-engineering",
                    "Composite job role: design and engineering professionals.",
                    List.of("architect", "civil-engineer", "structural-engineer",
                            "planning-engineer", "quantity-surveyor")),
            new CompositeJobRole("job-skilled-trades",
                    "Composite job role: skilled tradespeople and equipment operators.",
                    List.of("mason", "carpenter", "electrician", "plumber", "painter", "welder",
                            "scaffolder", "crane-operator", "equipment-operator", "driver")),
            new CompositeJobRole("job-general-workforce",
                    "Composite job role: general labour and site facilities.",
                    List.of("laborer", "helper", "site-cleaner", "security-guard")),
            new CompositeJobRole("job-finance-procurement",
                    "Composite job role: finance and procurement.",
                    List.of("accountant", "procurement-officer")),
            new CompositeJobRole("job-office-admin",
                    "Composite job role: office, HR and IT administration and support.",
                    List.of("hr-manager", "admin-staff", "office-assistant", "receptionist", "it-support")),
            new CompositeJobRole("job-leadership",
                    "Composite job role: executive leadership and project management.",
                    List.of("director", "owner-representative", "project-manager")),
            new CompositeJobRole("job-external-stakeholders",
                    "Composite job role: external, non-employee parties.",
                    List.of("client", "consultant", "contractor", "sub-contractor",
                            "vendor", "material-supplier")),
            new CompositeJobRole("job-early-career",
                    "Composite job role: interns, students and trainees.",
                    List.of("intern", "student", "trainee"))
    );

    /**
     * Codifies the H-1 composite job roles on every startup (both the fresh-realm and existing-realm
     * paths), fully idempotent and guarded so a failure only logs and never aborts startup. Each
     * composite is ensured to exist and to carry its granular occupation children; re-running only
     * fills in whatever is missing and never removes or reassigns anything.
     */
    private void ensureCompositeJobRoles() {
        try {
            log.info("Ensuring composite job roles over the granular occupation roles (H-1)");
            for (CompositeJobRole jobRole : COMPOSITE_JOB_ROLES) {
                ensureCompositeJobRole(jobRole);
            }
            log.info("Composite job roles reconcile complete");
        } catch (Exception e) {
            log.error("Failed to ensure composite job roles: {}", e.getMessage(), e);
        }
    }

    private void ensureCompositeJobRole(CompositeJobRole jobRole) {
        try {
            RealmResource realm = admin.realm(REALM_ID);

            // 1. Ensure the composite role itself exists (create if missing, never overwrite).
            boolean exists;
            try {
                realm.roles().get(jobRole.name).toRepresentation();
                exists = true;
            } catch (NotFoundException e) {
                exists = false;
            }
            if (!exists) {
                RoleRepresentation rep = new RoleRepresentation();
                rep.setName(jobRole.name);
                rep.setDescription(jobRole.description);
                rep.setComposite(true);
                realm.roles().create(rep);
                log.info("Created composite job role '{}'", jobRole.name);
            }

            RoleResource roleResource = realm.roles().get(jobRole.name);

            // 2. Determine which children are already attached, so we only add what is missing.
            Set<String> alreadyAttached = new HashSet<>();
            Set<RoleRepresentation> existingComposites = roleResource.getRealmRoleComposites();
            if (existingComposites != null) {
                for (RoleRepresentation r : existingComposites) {
                    alreadyAttached.add(r.getName());
                }
            }

            // 3. Attach any missing child occupation role that actually exists in the realm.
            List<RoleRepresentation> toAdd = new ArrayList<>();
            for (String child : jobRole.children) {
                if (alreadyAttached.contains(child)) {
                    continue;
                }
                try {
                    toAdd.add(realm.roles().get(child).toRepresentation());
                } catch (NotFoundException e) {
                    log.warn("Child occupation role '{}' not found; skipping from composite '{}'",
                            child, jobRole.name);
                }
            }

            if (!toAdd.isEmpty()) {
                roleResource.addComposites(toAdd);
                log.info("Added {} child role(s) to composite '{}': {}", toAdd.size(), jobRole.name,
                        toAdd.stream().map(RoleRepresentation::getName).toList());
            } else {
                log.info("Composite job role '{}' already carries all available children", jobRole.name);
            }
        } catch (Exception e) {
            log.error("Failed to ensure composite job role '{}': {}", jobRole.name, e.getMessage());
        }
    }

    private void assignServiceAccountRoles() {
        try {
            log.info("Assigning service account roles for client '{}'", appClientId);

            // 1. Find the client UUID (not clientId) for the application client
            List<org.keycloak.representations.idm.ClientRepresentation> clients = admin.realm(REALM_ID).clients().findByClientId(appClientId);
            if (clients.isEmpty()) {
                log.error("Client '{}' not found in realm '{}'", appClientId, REALM_ID);
                return;
            }
            org.keycloak.representations.idm.ClientRepresentation appClientRep = clients.get(0);
            org.keycloak.admin.client.resource.ClientResource appClientResource = admin.realm(REALM_ID).clients().get(appClientRep.getId());

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
            UserResource serviceAccountUserResource = admin.realm(REALM_ID).users().get(serviceAccountUser.getId());

            // 4. Find the 'realm-management' client UUID
            List<org.keycloak.representations.idm.ClientRepresentation> realmMgmtClients = admin.realm(REALM_ID).clients().findByClientId("realm-management");
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
                    RoleRepresentation role = admin.realm(REALM_ID).clients().get(realmMgmtClientRep.getId()).roles().get(roleName).toRepresentation();
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

            masterKeycloak.realms().create(realmRepresentationToImport);
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

        try (Response response = admin.realm(REALM_ID).users().create(userRepresentation)) {
            String userId = null;
            if (response.getStatus() == 201) { // CREATED
                userId = CreatedResponseUtil.getCreatedId(response);
                log.info("User '{}' created with id {}", user.getUserName(), userId);
            } else if (response.getStatus() == 409) { // CONFLICT
                log.warn("User '{}' already exists. Fetching to assign roles if needed.", user.getUserName());
                List<UserRepresentation> users = admin.realm(REALM_ID).users().search(user.getUserName());
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
                UserResource userResource = admin.realm(REALM_ID).users().get(userId);
                String roleName = user.isAdmin() ? "admin" : "user";
                List<RoleRepresentation> rolesToAdd =
                        Collections.singletonList(admin.realm(REALM_ID).roles().get(roleName).toRepresentation());
                userResource.roles().realmLevel().add(rolesToAdd);
                log.info("Role '{}' assigned to user '{}'", roleName, user.getUserName());
            }
        }
    }

    private void ensureAuthorizationSetup() {
        try {
            log.info("Ensuring authorization setup for client '{}'", appClientId);

            List<org.keycloak.representations.idm.ClientRepresentation> clients =
                    admin.realm(REALM_ID).clients().findByClientId(appClientId);
            if (clients.isEmpty()) {
                log.warn("Client '{}' not found, skipping authorization setup", appClientId);
                return;
            }

            ClientResource clientResource = admin.realm(REALM_ID).clients().get(clients.get(0).getId());

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
            RoleRepresentation userRole = admin.realm(REALM_ID).roles().get("user").toRepresentation();
            policy.addRole(userRole.getId(), false);
        } catch (Exception e) {
            log.warn("Realm role 'user' not found, skipping from Default Policy");
        }
        try {
            RoleRepresentation adminRole = admin.realm(REALM_ID).roles().get("admin").toRepresentation();
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
            RoleRepresentation userRole = admin.realm(REALM_ID).roles().get("user").toRepresentation();

            List<UserRepresentation> allUsers = admin.realm(REALM_ID).users().list();
            for (UserRepresentation u : allUsers) {
                List<RoleRepresentation> realmRoles = admin.realm(REALM_ID).users()
                        .get(u.getId()).roles().realmLevel().listAll();
                boolean hasRole = realmRoles.stream()
                        .anyMatch(r -> "user".equals(r.getName()) || "admin".equals(r.getName()));
                if (!hasRole) {
                    admin.realm(REALM_ID).users().get(u.getId()).roles()
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
         masterKeycloak.realm(REALM_ID).remove();
        } catch (NotFoundException e) {
            log.error("Failed to reset Keycloak", e);
        }
    }
}
