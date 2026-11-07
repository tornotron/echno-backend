package org.tornotron.echno_backend.common.identity.keycloak;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.identity.ConditionalOnKeycloak;

import java.util.Arrays;
import java.util.List;

/**
 * Refuses to start on Keycloak with its settings blank.
 *
 * <p>The placeholders in {@code application.yml} default to empty, so another provider can start
 * without them, which leaves this as the check they used to be. Keycloak is also the default, so this
 * is what stops a stack that forgot to name its provider from coming up half configured.
 *
 * <p>It runs as a {@link BeanFactoryPostProcessor} to fail before any bean is built: before a database
 * migration runs, and before a client is built from a blank address.
 */
@Component
@ConditionalOnKeycloak
public class KeycloakSettingsValidator implements BeanFactoryPostProcessor, EnvironmentAware {

    /** Everything the adapter and the Keycloak-only machinery read that has no default of its own. */
    static final List<String> REQUIRED = List.of(
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
            "jwt.auth.converter.resource-id",
            "keycloak-initializer.master-realm",
            "keycloak-initializer.application-realm",
            "keycloak-initializer.client-id",
            "keycloak-initializer.username",
            "keycloak-initializer.password",
            "keycloak-initializer.url",
            "keycloak.issuer-uri",
            "keycloak.frontend.client-id",
            "keycloak.frontend.redirect-uri",
            "keycloak.frontend.web-origin",
            "keycloak.client-id",
            "keycloak.redirect-uri",
            "keycloak.web-origin",
            "keycloak.secret",
            "keycloak.admin.userName",
            "keycloak.admin.email",
            "keycloak.admin.password",
            "keycloak.admin.firstName",
            "keycloak.admin.lastName",
            "keycloak.service.userName",
            "keycloak.service.email",
            "keycloak.service.password",
            "keycloak.service.firstName",
            "keycloak.service.lastName");

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        List<String> blank = REQUIRED.stream().filter(name -> isBlank(environment.getProperty(name))).toList();
        if (!blank.isEmpty()) {
            throw new IllegalStateException("echno.identity.provider is keycloak, but these settings are blank: "
                    + String.join(", ", blank)
                    + ". Set them, or set ECHNO_IDENTITY_PROVIDER to the provider this stack uses.");
        }
    }

    /** A few settings are two placeholders joined by a comma, so a comma alone still means blank. */
    private static boolean isBlank(String value) {
        return value == null || Arrays.stream(value.split(",")).allMatch(String::isBlank);
    }
}
