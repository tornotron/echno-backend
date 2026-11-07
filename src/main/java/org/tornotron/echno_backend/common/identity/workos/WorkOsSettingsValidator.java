package org.tornotron.echno_backend.common.identity.workos;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.identity.ConditionalOnWorkOs;

import java.util.Arrays;
import java.util.List;

/**
 * Refuses to start on WorkOS with any of its settings blank. A token is only accepted with the
 * audience and an issuer this stack names, so a blank one is a stack that would accept tokens meant
 * for something else, and it fails here rather than at the first request.
 *
 * <p>Runs as a {@link BeanFactoryPostProcessor}, like the Keycloak one, so it fails before any bean is
 * built and before a database migration runs.
 */
@Component
@ConditionalOnWorkOs
public class WorkOsSettingsValidator implements BeanFactoryPostProcessor, EnvironmentAware {

    static final List<String> REQUIRED = List.of(
            "echno.identity.workos.api-key",
            "echno.identity.workos.client-id",
            "echno.identity.workos.api-url",
            "echno.security.jwt.audience",
            "echno.security.jwt.accepted-issuers");

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        List<String> blank = REQUIRED.stream().filter(name -> isBlank(environment.getProperty(name))).toList();
        if (!blank.isEmpty()) {
            throw new IllegalStateException("echno.identity.provider is workos, but these settings are blank: "
                    + String.join(", ", blank)
                    + ". Set WORKOS_API_KEY, WORKOS_CLIENT_ID, ECHNO_JWT_AUDIENCE and ECHNO_JWT_ACCEPTED_ISSUERS.");
        }
    }

    /** A list setting of commas alone is still blank. */
    private static boolean isBlank(String value) {
        return value == null || Arrays.stream(value.split(",")).allMatch(String::isBlank);
    }
}
