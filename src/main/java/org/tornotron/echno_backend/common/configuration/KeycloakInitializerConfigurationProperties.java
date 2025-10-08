package org.tornotron.echno_backend.common.configuration;

import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "keycloak-initializer")
public class KeycloakInitializerConfigurationProperties {

    @Getter(AccessLevel.NONE)
    private boolean initializeOnStartup;

    public boolean isInitializeOnStartup() {
        return initializeOnStartup;
    }

    private String masterRealm;

    private String applicationRealm;

    private String clientId;

    private String username;

    private String password;

    private String url;

}
