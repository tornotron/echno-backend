package org.tornotron.echno_backend.common.configuration;

import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KeycloakConfig {

    @Autowired
    KeycloakInitializerConfigurationProperties keycloakInitializerConfigurationProperties;

    // Client id of the realm-scoped initializer service-account client that steady-state reconcile
    // authenticates as. Defaults to 'echno-initializer'.
    @Value("${keycloak.initializer.service-client-id:echno-initializer}")
    private String initializerServiceClientId;

    // Secret of the initializer service-account client. Empty until the environment has been
    // bootstrapped and the secret provisioned; the master fallback provisions it on first run.
    @Value("${keycloak.initializer.service-client-secret:}")
    private String initializerServiceClientSecret;

    /**
     * Bootstrap-only admin client. Authenticates against the MASTER realm with the admin-cli
     * password grant (the privileged master credential, H-3). It is used ONLY to bring a fresh or
     * not-yet-migrated environment up to the point where the realm-scoped initializer client
     * exists: creating the application realm and provisioning the echno-initializer client. No
     * steady-state deploy authenticates with this bean.
     */
    @Bean
    protected Keycloak masterKeycloak() {
        return KeycloakBuilder.builder()
                .realm(keycloakInitializerConfigurationProperties.getMasterRealm())
                .clientId(keycloakInitializerConfigurationProperties.getClientId())
                .username(keycloakInitializerConfigurationProperties.getUsername())
                .password(keycloakInitializerConfigurationProperties.getPassword())
                .serverUrl(keycloakInitializerConfigurationProperties.getUrl())
                .build();
    }

    /**
     * Builds a realm-scoped admin client that authenticates as the echno-initializer service
     * account with the client-credentials grant. It holds the realm-admin composite on the
     * application realm's realm-management client, i.e. full administrative authority over that one
     * realm and no access to master (so it cannot create or delete realms). Built on demand rather
     * than as a singleton bean: it can obtain a token only once the realm and the initializer
     * client already exist, which is not guaranteed at context-startup time.
     */
    public Keycloak buildScopedKeycloak() {
        return KeycloakBuilder.builder()
                .serverUrl(keycloakInitializerConfigurationProperties.getUrl())
                .realm(keycloakInitializerConfigurationProperties.getApplicationRealm())
                .clientId(initializerServiceClientId)
                .clientSecret(initializerServiceClientSecret)
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .build();
    }

    public String getInitializerServiceClientId() {
        return initializerServiceClientId;
    }

    public String getInitializerServiceClientSecret() {
        return initializerServiceClientSecret;
    }
}
