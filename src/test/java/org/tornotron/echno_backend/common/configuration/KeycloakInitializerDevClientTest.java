package org.tornotron.echno_backend.common.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.ClientsResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Light unit test (no Spring context, no Keycloak container) for the dev-fixture gate added in #451:
 * the local-dev client, account and development organization must only be reconciled when
 * keycloak.dev-client-enabled is true.
 */
@ExtendWith(MockitoExtension.class)
class KeycloakInitializerDevClientTest {

    private static final String REALM = "echno-realm";
    private static final String DEV_CLIENT_UUID = "99999999-8888-7777-6666-555555555555";

    @Mock
    private Keycloak masterKeycloak;

    @Mock
    private Keycloak adminClient;

    @Mock
    private KeycloakInitializerConfigurationProperties properties;

    @Mock
    private KeycloakConfig keycloakConfig;

    @Mock
    private DevFixtureProvisioner devFixtureProvisioner;

    private KeycloakInitializer newInitializer() {
        KeycloakInitializer initializer = new KeycloakInitializer(
                masterKeycloak, properties, new ObjectMapper(), keycloakConfig, devFixtureProvisioner);
        ReflectionTestUtils.setField(initializer, "admin", adminClient);
        ReflectionTestUtils.setField(KeycloakInitializer.class, "REALM_ID", REALM);
        return initializer;
    }

    @Test
    void devClientDisabled_leavesKeycloakUntouched() {
        KeycloakInitializer initializer = newInitializer();
        ReflectionTestUtils.setField(initializer, "devClientEnabled", false);

        ReflectionTestUtils.invokeMethod(initializer, "ensureDevClient");

        // Gate off: not a single admin call is made against the realm, and no application row is
        // written either. The fixture now touches the database, so the gate has to cover both.
        verifyNoInteractions(adminClient);
        verifyNoInteractions(devFixtureProvisioner);
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

    /**
     * The dev client's update has to send a complete representation like every other client update
     * in this class. Keycloak reads an absent boolean on update as false, so a partial one turns off
     * whatever it omits: that is what disabled authorization services and deleted a client's resource
     * server live. This update used to go straight to Keycloak, around preserveUnsetCapabilities.
     */
    @Test
    void devClientUpdateCarriesTheCapabilitiesItsRepresentationLeavesUnset() {
        RealmResource realmResource = mock(RealmResource.class);
        ClientsResource clientsResource = mock(ClientsResource.class);
        ClientResource clientResource = mock(ClientResource.class);

        ClientRepresentation live = new ClientRepresentation();
        live.setId(DEV_CLIENT_UUID);
        live.setClientId("echno-web-local");
        live.setAuthorizationServicesEnabled(true);
        live.setFrontchannelLogout(true);
        live.setImplicitFlowEnabled(true);
        live.setBearerOnly(false);

        when(adminClient.realm(REALM)).thenReturn(realmResource);
        when(realmResource.clients()).thenReturn(clientsResource);
        when(clientsResource.findByClientId("echno-web-local")).thenReturn(List.of(live));
        when(clientsResource.get(DEV_CLIENT_UUID)).thenReturn(clientResource);

        KeycloakInitializer initializer = newInitializer();
        ReflectionTestUtils.invokeMethod(initializer, "ensureDevWebLocalClient");

        ArgumentCaptor<ClientRepresentation> sent = ArgumentCaptor.forClass(ClientRepresentation.class);
        verify(clientResource).update(sent.capture());
        ClientRepresentation update = sent.getValue();

        assertThat(update.getId()).isEqualTo(DEV_CLIENT_UUID);
        assertThat(update.getAuthorizationServicesEnabled())
                .as("an omitted authorizationServicesEnabled deletes the client's resource server")
                .isTrue();
        assertThat(update.isFrontchannelLogout()).isTrue();
        assertThat(update.isImplicitFlowEnabled()).isTrue();
        assertThat(update.isBearerOnly()).isFalse();
        // The values the dev client does state are still its own, not the live client's.
        assertThat(update.isPublicClient()).isTrue();
        assertThat(update.getRedirectUris()).containsExactly("http://localhost:3000/*");
    }

    /**
     * The fixture identifies its own account by email, so that an account that merely shares the
     * username is never adopted, password-reset or wired to the fixture's organization. The username
     * used to be a seeded QA persona's, which is exactly how a developer ended up inside her account.
     */
    @Test
    void onlyTheFixturesOwnAccountIsRecognised() {
        UserRepresentation fixture = new UserRepresentation();
        fixture.setUsername("echno-local-dev");
        fixture.setEmail("local-dev@echno.local");

        UserRepresentation somebodyElse = new UserRepresentation();
        somebodyElse.setUsername("echno-local-dev");
        somebodyElse.setEmail("amelia@echno.com");

        UserRepresentation noEmail = new UserRepresentation();
        noEmail.setUsername("echno-local-dev");

        assertThat(KeycloakInitializer.isDevFixtureAccount(fixture)).isTrue();
        assertThat(KeycloakInitializer.isDevFixtureAccount(somebodyElse)).isFalse();
        assertThat(KeycloakInitializer.isDevFixtureAccount(noEmail)).isFalse();
        assertThat(KeycloakInitializer.isDevFixtureAccount(null)).isFalse();
    }
}
