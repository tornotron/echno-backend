package org.tornotron.echno_backend.common.configuration;

import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KeycloakConfig {

    @Autowired
    KeycloakInitializerConfigurationProperties keycloakInitializerConfigurationProperties;

    @Bean
    protected Keycloak keycloak() {
        return KeycloakBuilder.builder()
//                .grantType(OAuth2Constants.)
                .realm(keycloakInitializerConfigurationProperties.getMasterRealm())
                .clientId(keycloakInitializerConfigurationProperties.getClientId())
                .username(keycloakInitializerConfigurationProperties.getUsername())
                .password(keycloakInitializerConfigurationProperties.getPassword())
                .serverUrl(keycloakInitializerConfigurationProperties.getUrl())
                .build();
    }
}
