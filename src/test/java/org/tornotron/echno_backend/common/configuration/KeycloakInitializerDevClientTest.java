package org.tornotron.echno_backend.common.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.Keycloak;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Light unit test (no Spring context, no Keycloak container) for the dev-client gate added in #451:
 * the local-dev client/account must only be reconciled when keycloak.dev-client-enabled is true.
 */
@ExtendWith(MockitoExtension.class)
class KeycloakInitializerDevClientTest {

    private static final String REALM = "echno-realm";

    @Mock
    private Keycloak masterKeycloak;

    @Mock
    private Keycloak adminClient;

    @Mock
    private KeycloakInitializerConfigurationProperties properties;

    @Mock
    private KeycloakConfig keycloakConfig;

    private KeycloakInitializer newInitializer() {
        KeycloakInitializer initializer =
                new KeycloakInitializer(masterKeycloak, properties, new ObjectMapper(), keycloakConfig);
        ReflectionTestUtils.setField(initializer, "admin", adminClient);
        ReflectionTestUtils.setField(KeycloakInitializer.class, "REALM_ID", REALM);
        return initializer;
    }

    @Test
    void devClientDisabled_leavesKeycloakUntouched() {
        KeycloakInitializer initializer = newInitializer();
        ReflectionTestUtils.setField(initializer, "devClientEnabled", false);

        ReflectionTestUtils.invokeMethod(initializer, "ensureDevClient");

        // Gate off: not a single admin call is made against the realm.
        verifyNoInteractions(adminClient);
    }

    @Test
    void devClientEnabled_reconcilesAgainstTheRealm() {
        KeycloakInitializer initializer = newInitializer();
        ReflectionTestUtils.setField(initializer, "devClientEnabled", true);
        ReflectionTestUtils.setField(initializer, "devUserPassword", "test-dev");

        ReflectionTestUtils.invokeMethod(initializer, "ensureDevClient");

        // Gate on: the reconcile reaches the realm to create/sync the dev client + account. The deep
        // admin chain is unstubbed, so the guarded body no-ops after this call, which is enough to
        // prove the flag did not short-circuit.
        verify(adminClient, atLeastOnce()).realm(REALM);
    }
}
