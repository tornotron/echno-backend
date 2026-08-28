package org.tornotron.echno_backend.common.configuration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Proves the H-3 scoped-admin redesign against a real Keycloak container.
 *
 * <p>Two scenarios are covered:
 * <ol>
 *   <li>Fresh bootstrap: the initializer creates the realm with the master credential, provisions a
 *       realm-scoped echno-initializer client that holds realm-admin, and the scoped client can then
 *       authenticate with client_credentials.</li>
 *   <li>The exact failure that reverted the first attempt: an echno-initializer client that already
 *       exists with the WRONG secret (so client_credentials is rejected as unauthorized_client). The
 *       initializer must repair it (delete + recreate with the configured secret, which Keycloak
 *       validates) so a freshly built scoped client can obtain a token.</li>
 * </ol>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KeycloakInitializerScopedAdminIT {

    private static final String TEST_REALM = "echno-it-realm";
    private static final String INIT_CLIENT_ID = "echno-initializer";
    private static final String INIT_SECRET = "echno-initializer-it-secret";
    private static final String WRONG_SECRET = "deliberately-wrong-secret";
    private static final String APP_CLIENT_ID = "echno-backend";
    // Matches the secret the staged realm fixture gives the backend client, i.e. what the
    // deployment "knows". Placeholder only, nothing here resembles a real credential.
    private static final String APP_CLIENT_SECRET = "echno-backend-it-secret";
    private static final String REALM_MANAGEMENT = "realm-management";
    private static final String REALM_ADMIN = "realm-admin";
    private static final String COMPOSITE_JOB_ROLE = "job-leadership";

    // Pin the server image to the tag matching the keycloak-admin-client version so the container
    // API and the client stay in lockstep.
    private static final KeycloakContainer KEYCLOAK =
            new KeycloakContainer("quay.io/keycloak/keycloak:26.0.7");

    private static Path configDir;
    private static Keycloak master;
    private static KeycloakInitializerConfigurationProperties props;
    private static KeycloakConfig keycloakConfig;

    @BeforeAll
    static void setUp() throws Exception {
        KEYCLOAK.start();

        // Stage the realm/user config files the initializer reads from disk.
        configDir = Files.createTempDirectory("keycloak-it-config");
        copyResource("keycloak-it/init-keycloak.json", configDir.resolve("init-keycloak.json"));
        copyResource("keycloak-it/init-keycloak-users.json", configDir.resolve("init-keycloak-users.json"));

        master = KeycloakBuilder.builder()
                .serverUrl(KEYCLOAK.getAuthServerUrl())
                .realm("master")
                .clientId("admin-cli")
                .username(KEYCLOAK.getAdminUsername())
                .password(KEYCLOAK.getAdminPassword())
                .grantType(OAuth2Constants.PASSWORD)
                .build();

        props = new KeycloakInitializerConfigurationProperties();
        props.setMasterRealm("master");
        props.setApplicationRealm(TEST_REALM);
        props.setClientId("admin-cli");
        props.setUsername(KEYCLOAK.getAdminUsername());
        props.setPassword(KEYCLOAK.getAdminPassword());
        props.setUrl(KEYCLOAK.getAuthServerUrl());

        keycloakConfig = new KeycloakConfig();
        ReflectionTestUtils.setField(keycloakConfig, "keycloakInitializerConfigurationProperties", props);
        ReflectionTestUtils.setField(keycloakConfig, "initializerServiceClientId", INIT_CLIENT_ID);
        ReflectionTestUtils.setField(keycloakConfig, "initializerServiceClientSecret", INIT_SECRET);

        // REALM_ID is a static field normally set from ApplicationReadyEvent; set it directly here.
        ReflectionTestUtils.setField(KeycloakInitializer.class, "REALM_ID", TEST_REALM);
    }

    @AfterAll
    static void tearDown() {
        if (master != null) {
            master.close();
        }
        KEYCLOAK.stop();
    }

    private static void copyResource(String classpath, Path target) throws Exception {
        try (InputStream in = KeycloakInitializerScopedAdminIT.class.getClassLoader().getResourceAsStream(classpath)) {
            assertThat(in).as("test resource %s", classpath).isNotNull();
            Files.copy(in, target);
        }
    }

    private KeycloakInitializer newInitializer() {
        // Mirror Spring Boot's auto-configured mapper, which ignores unknown properties, so the
        // Keycloak RealmRepresentation config parses the same way it does at runtime.
        ObjectMapper lenientMapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        KeycloakInitializer initializer =
                new KeycloakInitializer(master, props, lenientMapper, keycloakConfig);
        ReflectionTestUtils.setField(initializer, "configOutput", configDir.toString());
        ReflectionTestUtils.setField(initializer, "appClientId", APP_CLIENT_ID);
        return initializer;
    }

    private boolean realmExists() {
        try {
            master.realm(TEST_REALM).toRepresentation();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Builds a scoped client_credentials client with the given secret and returns the access token string. */
    private String fetchScopedToken(String secret) {
        try (Keycloak scoped = KeycloakBuilder.builder()
                .serverUrl(KEYCLOAK.getAuthServerUrl())
                .realm(TEST_REALM)
                .clientId(INIT_CLIENT_ID)
                .clientSecret(secret)
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .build()) {
            return scoped.tokenManager().getAccessTokenString();
        }
    }

    /** Builds a client_credentials client for the backend client and returns the access token string. */
    private String fetchAppToken(String secret) {
        try (Keycloak app = KeycloakBuilder.builder()
                .serverUrl(KEYCLOAK.getAuthServerUrl())
                .realm(TEST_REALM)
                .clientId(APP_CLIENT_ID)
                .clientSecret(secret)
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .build()) {
            return app.tokenManager().getAccessTokenString();
        }
    }

    @Test
    @Order(1)
    void freshBootstrapCreatesRealmAndScopedInitializerClient() {
        KeycloakInitializer initializer = newInitializer();

        initializer.init(false);

        // Realm was created.
        assertThat(master.realm(TEST_REALM).toRepresentation()).isNotNull();

        // The scoped initializer client exists and is confidential with a service account.
        List<ClientRepresentation> initClients = master.realm(TEST_REALM).clients().findByClientId(INIT_CLIENT_ID);
        assertThat(initClients).hasSize(1);
        ClientRepresentation initClient = initClients.get(0);
        assertThat(initClient.isPublicClient()).isFalse();
        assertThat(initClient.isServiceAccountsEnabled()).isTrue();

        // Its service account actually holds realm-admin (from realm-management).
        UserRepresentation serviceAccount =
                master.realm(TEST_REALM).clients().get(initClient.getId()).getServiceAccountUser();
        assertThat(serviceAccount).isNotNull();
        String realmMgmtUuid =
                master.realm(TEST_REALM).clients().findByClientId(REALM_MANAGEMENT).get(0).getId();
        List<RoleRepresentation> saClientRoles = master.realm(TEST_REALM).users()
                .get(serviceAccount.getId()).roles().clientLevel(realmMgmtUuid).listAll();
        assertThat(saClientRoles).extracting(RoleRepresentation::getName).contains(REALM_ADMIN);

        // Reconcile completed: a composite job role (H-1) was created through the scoped admin.
        assertThat(master.realm(TEST_REALM).roles().get(COMPOSITE_JOB_ROLE).toRepresentation()).isNotNull();

        // The scoped client can independently obtain a client_credentials token with the config secret.
        assertThatCode(() -> {
            String token = fetchScopedToken(INIT_SECRET);
            assertThat(token).isNotBlank();
        }).doesNotThrowAnyException();
    }

    /**
     * The exact live failure the reverted attempt did not cover: an echno-initializer client that
     * already exists with a secret that does NOT match the configured one. The client_credentials
     * grant is rejected until the initializer repairs the client by recreating it with a validated
     * secret. This test drives that repair against a real Keycloak.
     */
    @Test
    @Order(2)
    void existingClientWithWrongSecret_isRepairedAndScopedAuthWorks() {
        // The realm exists from the fresh bootstrap; guard defensively in case of isolated execution.
        if (!realmExists()) {
            newInitializer().init(false);
        }

        // Replace the good client with one carrying a DELIBERATELY WRONG secret, reproducing the
        // broken live state: the stored credential differs from the configured one.
        List<ClientRepresentation> current = master.realm(TEST_REALM).clients().findByClientId(INIT_CLIENT_ID);
        for (ClientRepresentation c : current) {
            master.realm(TEST_REALM).clients().get(c.getId()).remove();
        }
        ClientRepresentation broken = new ClientRepresentation();
        broken.setClientId(INIT_CLIENT_ID);
        broken.setEnabled(true);
        broken.setPublicClient(false);
        broken.setServiceAccountsEnabled(true);
        broken.setStandardFlowEnabled(false);
        broken.setDirectAccessGrantsEnabled(false);
        broken.setSecret(WRONG_SECRET);
        String brokenUuid;
        try (Response response = master.realm(TEST_REALM).clients().create(broken)) {
            assertThat(response.getStatus()).isEqualTo(201);
            brokenUuid = CreatedResponseUtil.getCreatedId(response);
        }
        assertThat(brokenUuid).isNotBlank();

        // Precondition: with the wrong stored secret, the configured secret cannot authenticate.
        assertThatCode(() -> fetchScopedToken(INIT_SECRET))
                .as("client_credentials must fail while the stored secret is wrong")
                .isInstanceOf(Exception.class);

        // Run the initializer with the correct configured secret. It must detect the unusable scoped
        // client, delete + recreate it with a validated secret, and finish the reconcile.
        assertThatCode(() -> newInitializer().init(false)).doesNotThrowAnyException();

        // (a) The client was recreated (fresh UUID, not an in-place secret update).
        List<ClientRepresentation> repaired = master.realm(TEST_REALM).clients().findByClientId(INIT_CLIENT_ID);
        assertThat(repaired).hasSize(1);
        assertThat(repaired.get(0).getId()).isNotEqualTo(brokenUuid);

        // (b) THE assertion that failed live: a freshly built scoped client can now obtain a
        // client_credentials access token with the configured secret, with no exception.
        assertThatCode(() -> {
            String token = fetchScopedToken(INIT_SECRET);
            assertThat(token).isNotBlank();
        }).doesNotThrowAnyException();

        // (c) Reconcile artifacts still exist (composite job role from H-1).
        assertThat(master.realm(TEST_REALM).roles().get(COMPOSITE_JOB_ROLE).toRepresentation()).isNotNull();

        // And the repaired service account holds realm-admin again.
        UserRepresentation serviceAccount =
                master.realm(TEST_REALM).clients().get(repaired.get(0).getId()).getServiceAccountUser();
        assertThat(serviceAccount).isNotNull();
        String realmMgmtUuid =
                master.realm(TEST_REALM).clients().findByClientId(REALM_MANAGEMENT).get(0).getId();
        List<RoleRepresentation> saClientRoles = master.realm(TEST_REALM).users()
                .get(serviceAccount.getId()).roles().clientLevel(realmMgmtUuid).listAll();
        assertThat(saClientRoles).extracting(RoleRepresentation::getName).contains(REALM_ADMIN);
    }

    /**
     * The backend client's own secret, which is what KeycloakGroupService authenticates with on
     * every employee-onboarding and org-role call. Two things are proven against a real Keycloak:
     *
     * <ol>
     *   <li>A realm built from the generated JSON gives the backend client the secret the
     *       deployment already holds, so the grant works from the very first boot rather than
     *       against a Keycloak-generated random value nothing else knows.</li>
     *   <li>When Keycloak's stored copy drifts, the reconcile repairs it in place, and the update it
     *       sends is a complete representation: authorization services stay enabled and the client's
     *       resource server survives. Sending only the secret is what disabled authorization
     *       services and deleted the resource server live, failing every authenticated request.</li>
     * </ol>
     */
    @Test
    @Order(3)
    void backendClientSecretDrift_isRepairedWithoutLosingAuthorizationServices() {
        if (!realmExists()) {
            newInitializer().init(false);
        }

        // (1) The realm was built from the JSON, so the configured secret already authenticates.
        assertThatCode(() -> assertThat(fetchAppToken(APP_CLIENT_SECRET)).isNotBlank())
                .as("a realm created from the generated JSON must accept the configured backend secret")
                .doesNotThrowAnyException();

        String appUuid = master.realm(TEST_REALM).clients().findByClientId(APP_CLIENT_ID).get(0).getId();
        assertThat(master.realm(TEST_REALM).clients().get(appUuid).authorization().getSettings())
                .as("the backend client starts with an authorization resource server")
                .isNotNull();

        // Drift the stored secret away from the configured one, exactly as happened live. This uses
        // Keycloak's own regenerate endpoint, so it is a genuine drift and not a doctored fixture.
        master.realm(TEST_REALM).clients().get(appUuid).generateNewSecret();
        assertThatCode(() -> fetchAppToken(APP_CLIENT_SECRET))
                .as("client_credentials must fail while the stored secret has drifted")
                .isInstanceOf(Exception.class);

        assertThatCode(() -> newInitializer().init(false)).doesNotThrowAnyException();

        // (2a) The grant works again with the secret the deployment holds.
        assertThatCode(() -> assertThat(fetchAppToken(APP_CLIENT_SECRET)).isNotBlank())
                .as("the reconcile must restore the configured backend secret")
                .doesNotThrowAnyException();

        // (2b) The repair was an in-place update, not a delete and recreate.
        ClientRepresentation repairedApp =
                master.realm(TEST_REALM).clients().findByClientId(APP_CLIENT_ID).get(0);
        assertThat(repairedApp.getId()).isEqualTo(appUuid);

        // (2c) The update carried a complete representation, so neither capability was cleared and
        // the resource server the whole authorization stack depends on is still there.
        assertThat(repairedApp.getAuthorizationServicesEnabled()).isTrue();
        assertThat(repairedApp.isServiceAccountsEnabled()).isTrue();
        assertThat(master.realm(TEST_REALM).clients().get(appUuid).authorization().getSettings())
                .as("the client's resource server must survive a secret repair")
                .isNotNull();
    }
}
