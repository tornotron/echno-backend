package org.tornotron.echno_backend.common.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * Proves the H-3 scoped-admin redesign against a real Keycloak container: the initializer bootstraps
 * a fresh realm with the master credential, provisions a realm-scoped echno-initializer client that
 * holds realm-admin, and then on every subsequent startup reconciles through that scoped client
 * without ever touching the master credential.
 *
 * The test is hermetic: its own realm name and config files, no dependency on the application
 * database or Spring context.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KeycloakInitializerScopedAdminIT {

    private static final String TEST_REALM = "echno-it-realm";
    private static final String INIT_CLIENT_ID = "echno-initializer";
    private static final String INIT_SECRET = "echno-initializer-it-secret";
    private static final String APP_CLIENT_ID = "echno-backend";
    private static final String REALM_MANAGEMENT = "realm-management";
    private static final String REALM_ADMIN = "realm-admin";

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

    private KeycloakInitializer newInitializer(Keycloak masterClient) {
        KeycloakInitializer initializer =
                new KeycloakInitializer(masterClient, props, new ObjectMapper(), keycloakConfig);
        ReflectionTestUtils.setField(initializer, "configOutput", configDir.toString());
        ReflectionTestUtils.setField(initializer, "appClientId", APP_CLIENT_ID);
        return initializer;
    }

    @Test
    @Order(1)
    void freshBootstrapCreatesRealmAndScopedInitializerClient() {
        KeycloakInitializer initializer = newInitializer(master);

        initializer.init(false);

        // Realm was created.
        assertThat(master.realm(TEST_REALM).toRepresentation()).isNotNull();

        // The scoped initializer client exists and is confidential with a service account.
        var initClients = master.realm(TEST_REALM).clients().findByClientId(INIT_CLIENT_ID);
        assertThat(initClients).hasSize(1);
        var initClient = initClients.get(0);
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
        assertThat(master.realm(TEST_REALM).roles().get("job-leadership").toRepresentation()).isNotNull();

        // And the scoped client can independently authenticate with client_credentials and read the realm.
        try (Keycloak scoped = KeycloakBuilder.builder()
                .serverUrl(KEYCLOAK.getAuthServerUrl())
                .realm(TEST_REALM)
                .clientId(INIT_CLIENT_ID)
                .clientSecret(INIT_SECRET)
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .build()) {
            assertThat(scoped.realm(TEST_REALM).toRepresentation().getRealm()).isEqualTo(TEST_REALM);
        }
    }

    @Test
    @Order(2)
    void steadyStateReconcilesThroughScopedClientWithoutTouchingMaster() {
        // A master client that fails the test if any method is invoked on it. Steady-state reconcile
        // must go entirely through the scoped realm-admin, so this must never be dereferenced.
        Keycloak forbiddenMaster = mock(Keycloak.class, invocation -> {
            throw new AssertionError("Steady-state reconcile must not use the master credential; "
                    + "master method called: " + invocation.getMethod().getName());
        });

        KeycloakInitializer initializer = newInitializer(forbiddenMaster);

        // If the scoped-first path were not taken, the initializer would call the forbidden master
        // client (probe/create-realm) and this would throw.
        assertThatCode(() -> initializer.init(false)).doesNotThrowAnyException();

        // The realm and the scoped client are still intact after the second, master-free run.
        assertThat(master.realm(TEST_REALM).clients().findByClientId(INIT_CLIENT_ID)).hasSize(1);
        assertThat(master.realm(TEST_REALM).roles().get("job-leadership").toRepresentation()).isNotNull();
    }
}
