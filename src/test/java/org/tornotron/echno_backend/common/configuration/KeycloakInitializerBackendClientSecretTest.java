package org.tornotron.echno_backend.common.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.ClientsResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.token.TokenManager;
import org.keycloak.representations.idm.ClientRepresentation;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests (no Spring context, no Keycloak container) for the client sync in KeycloakInitializer.
 *
 * <p>Two behaviours are pinned here.
 *
 * <p>The first is the backend client's secret. Its value is known outside Keycloak, because
 * KeycloakGroupService authenticates as this client with the configured secret on every
 * employee-onboarding and org-role call. So the sync must not blindly preserve whatever Keycloak
 * holds: it proves the configured secret with a real client_credentials grant and pushes it back
 * only when that grant fails. Every other client keeps the preserve behaviour, because nothing
 * outside Keycloak knows those values and a deliberate rotation should survive a deploy.
 *
 * <p>The second is the more valuable one: the update must never be a partial representation.
 * Keycloak reads an absent boolean in a client update as false, so sending a representation without
 * authorizationServicesEnabled disables authorization services and deletes the client's resource
 * server, which fails every authenticated request until the backend restarts and rebuilds it. The
 * same trap on serviceAccountsEnabled would break the client_credentials grant this whole change
 * exists to protect. The failure is silent, total, and looks unrelated to a secret change, so it is
 * asserted directly.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KeycloakInitializerBackendClientSecretTest {

    private static final String REALM = "echno-realm";
    private static final String BACKEND_CLIENT_ID = "echno-backend-client";
    private static final String FRONTEND_CLIENT_ID = "echno-frontend-client";
    private static final String CLIENT_UUID = "11111111-2222-3333-4444-555555555555";

    // Placeholder values only. Nothing here is, or resembles, a real credential.
    private static final String CONFIGURED_SECRET = "configured-value";
    private static final String LIVE_SECRET = "live-value";

    @Mock
    private Keycloak masterKeycloak;

    @Mock
    private Keycloak adminClient;

    @Mock
    private Keycloak grantProbe;

    @Mock
    private TokenManager tokenManager;

    @Mock
    private RealmResource realmResource;

    @Mock
    private ClientsResource clientsResource;

    @Mock
    private ClientResource clientResource;

    @Mock
    private KeycloakInitializerConfigurationProperties properties;

    @Mock
    private KeycloakConfig keycloakConfig;

    private KeycloakInitializer initializer;

    @BeforeEach
    void setUp() {
        when(adminClient.realm(REALM)).thenReturn(realmResource);
        when(realmResource.clients()).thenReturn(clientsResource);
        when(clientsResource.get(CLIENT_UUID)).thenReturn(clientResource);
        when(grantProbe.tokenManager()).thenReturn(tokenManager);

        initializer = new KeycloakInitializer(masterKeycloak, properties, new ObjectMapper(), keycloakConfig);
        ReflectionTestUtils.setField(initializer, "admin", adminClient);
        ReflectionTestUtils.setField(initializer, "appClientId", BACKEND_CLIENT_ID);
        ReflectionTestUtils.setField(KeycloakInitializer.class, "REALM_ID", REALM);
    }

    /** The live client as Keycloak currently holds it: confidential, with both capabilities on. */
    private ClientRepresentation liveBackendClient() {
        ClientRepresentation live = new ClientRepresentation();
        live.setId(CLIENT_UUID);
        live.setClientId(BACKEND_CLIENT_ID);
        live.setSecret(LIVE_SECRET);
        live.setPublicClient(false);
        live.setServiceAccountsEnabled(true);
        live.setAuthorizationServicesEnabled(true);
        live.setStandardFlowEnabled(true);
        return live;
    }

    /** The client as the generated realm JSON describes it, carrying the configured secret. */
    private ClientRepresentation configuredBackendClient() {
        ClientRepresentation fromConfig = new ClientRepresentation();
        fromConfig.setClientId(BACKEND_CLIENT_ID);
        fromConfig.setSecret(CONFIGURED_SECRET);
        fromConfig.setPublicClient(false);
        fromConfig.setServiceAccountsEnabled(true);
        fromConfig.setAuthorizationServicesEnabled(true);
        fromConfig.setStandardFlowEnabled(true);
        return fromConfig;
    }

    private void keycloakHolds(ClientRepresentation live) {
        when(clientsResource.findByClientId(live.getClientId())).thenReturn(List.of(live));
    }

    private void grantSucceeds() {
        when(keycloakConfig.buildClientCredentialsKeycloak(anyString(), anyString())).thenReturn(grantProbe);
        when(tokenManager.getAccessTokenString()).thenReturn("an-access-token");
    }

    private void grantFails() {
        when(keycloakConfig.buildClientCredentialsKeycloak(anyString(), anyString())).thenReturn(grantProbe);
        when(tokenManager.getAccessTokenString())
                .thenThrow(new RuntimeException("HTTP 401 Unauthorized: unauthorized_client"));
    }

    private ClientRepresentation captureUpdate() {
        ArgumentCaptor<ClientRepresentation> sent = ArgumentCaptor.forClass(ClientRepresentation.class);
        verify(clientResource).update(sent.capture());
        return sent.getValue();
    }

    @Test
    void backendClientWhoseGrantSucceeds_keepsTheSecretKeycloakAlreadyHolds() {
        keycloakHolds(liveBackendClient());
        grantSucceeds();

        initializer.syncSingleClient(configuredBackendClient());

        // The grant proves Keycloak validates the configured secret, so there is nothing to repair
        // and the sync leaves the stored credential exactly as it was.
        assertThat(captureUpdate().getSecret()).isEqualTo(LIVE_SECRET);
    }

    @Test
    void backendClientWhoseGrantFails_restoresTheConfiguredSecret() {
        keycloakHolds(liveBackendClient());
        grantFails();

        initializer.syncSingleClient(configuredBackendClient());

        // Drift: Keycloak rejected the value the deployment holds, so the reconcile pushes it back
        // instead of preserving the drift forever.
        assertThat(captureUpdate().getSecret()).isEqualTo(CONFIGURED_SECRET);
    }

    @Test
    void nonBackendClient_keepsTheLiveSecretAndIsNeverProbed() {
        ClientRepresentation live = new ClientRepresentation();
        live.setId(CLIENT_UUID);
        live.setClientId(FRONTEND_CLIENT_ID);
        live.setSecret(LIVE_SECRET);
        live.setPublicClient(true);
        keycloakHolds(live);

        ClientRepresentation fromConfig = new ClientRepresentation();
        fromConfig.setClientId(FRONTEND_CLIENT_ID);
        fromConfig.setSecret(CONFIGURED_SECRET);
        fromConfig.setPublicClient(true);

        initializer.syncSingleClient(fromConfig);

        // "A rotated secret survives" stays the default everywhere nothing outside Keycloak knows
        // the value, and no grant is attempted for such a client.
        assertThat(captureUpdate().getSecret()).isEqualTo(LIVE_SECRET);
        verify(keycloakConfig, never()).buildClientCredentialsKeycloak(anyString(), anyString());
    }

    @Test
    void backendClientWithNoConfiguredSecret_leavesTheStoredSecretAlone() {
        keycloakHolds(liveBackendClient());

        ClientRepresentation fromConfig = configuredBackendClient();
        fromConfig.setSecret(null);

        initializer.syncSingleClient(fromConfig);

        assertThat(captureUpdate().getSecret()).isEqualTo(LIVE_SECRET);
        verify(keycloakConfig, never()).buildClientCredentialsKeycloak(anyString(), anyString());
    }

    @Test
    void updateNeverClearsAuthorizationServicesOrServiceAccounts() {
        keycloakHolds(liveBackendClient());
        grantSucceeds();

        // A representation that says nothing about the capability flags. Sent as it is, Keycloak
        // reads the absent booleans as false: authorization services go off, the client's resource
        // server is deleted, and every authenticated request fails until the backend restarts.
        ClientRepresentation partial = new ClientRepresentation();
        partial.setClientId(BACKEND_CLIENT_ID);
        partial.setSecret(CONFIGURED_SECRET);

        initializer.syncSingleClient(partial);

        ClientRepresentation sent = captureUpdate();
        assertThat(sent.getAuthorizationServicesEnabled()).isTrue();
        assertThat(sent.isServiceAccountsEnabled()).isTrue();
        assertThat(sent.isStandardFlowEnabled()).isTrue();
        assertThat(sent.isPublicClient()).isFalse();
    }

    @Test
    void secretRepairStillSendsACompleteRepresentation() {
        keycloakHolds(liveBackendClient());
        grantFails();

        // The dangerous combination: a drifted secret that has to be pushed, on a representation
        // that does not restate the capabilities. The repair must not be the thing that takes
        // authorization services down.
        ClientRepresentation partial = new ClientRepresentation();
        partial.setClientId(BACKEND_CLIENT_ID);
        partial.setSecret(CONFIGURED_SECRET);

        initializer.syncSingleClient(partial);

        ClientRepresentation sent = captureUpdate();
        assertThat(sent.getSecret()).isEqualTo(CONFIGURED_SECRET);
        assertThat(sent.getAuthorizationServicesEnabled()).isTrue();
        assertThat(sent.isServiceAccountsEnabled()).isTrue();
    }

    @Test
    void preserveUnsetCapabilities_neverOverwritesAValueTheConfigStates() {
        ClientRepresentation live = liveBackendClient();
        ClientRepresentation target = new ClientRepresentation();
        target.setClientId(BACKEND_CLIENT_ID);
        // The config deliberately turns a flag off; that intent must win over the live value.
        target.setStandardFlowEnabled(false);

        KeycloakInitializer.preserveUnsetCapabilities(target, live);

        assertThat(target.isStandardFlowEnabled()).isFalse();
        assertThat(target.getAuthorizationServicesEnabled()).isTrue();
        assertThat(target.isServiceAccountsEnabled()).isTrue();
    }

    @Test
    void syncKeepsTheLiveClientId() {
        keycloakHolds(liveBackendClient());
        grantSucceeds();

        initializer.syncSingleClient(configuredBackendClient());

        assertThat(captureUpdate().getId()).isEqualTo(CLIENT_UUID);
    }
}
