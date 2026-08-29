package org.tornotron.echno_backend.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.PropertyPlaceholderHelper;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Guards the two shipped configuration values that decide what an unauthenticated caller
 * can read, by evaluating them the way an environment that sets nothing would.
 *
 * <p>The point is the default rather than any one deployment. Both of these were once
 * permissive in application.yml and safe only because something outside this repository
 * closed them: the compose edge vhost returned 404 for the docs paths, and both
 * deployments set the health detail level by environment variable. That held until a
 * second ingress arrived. The k8s ingress carries no equivalent rule, so the OpenAPI
 * document, the full endpoint surface, was a public download on backend-k8s.echno.in for
 * as long as that host existed. A default that leans closed would have covered it without
 * anyone remembering to. See issue #569.
 *
 * <p>Reading the file rather than starting a context is deliberate: the question is what
 * application.yml ships, and a test that booted Spring would answer it for whatever
 * profile and property sources the test harness happens to supply. It also costs no test
 * context, which matters in a 1 GB test JVM (see {@link UnboundedRepositoryReadTest}).
 */
class ShippedConfigurationDefaultTest {

    /** Resolves {@code ${NAME:default}} the way an environment supplying nothing would. */
    private static final PropertyPlaceholderHelper UNSET_ENVIRONMENT =
            new PropertyPlaceholderHelper("${", "}", ":", '\\', true);

    @Test
    @DisplayName("the API docs are closed unless an environment asks for them")
    void apiDocsAreNotPublicByDefault() {
        String shipped = shippedValue("springdoc", "swagger-ui", "public-access");

        assertThat(Boolean.parseBoolean(resolveWithNothingSet(shipped)))
                .as("springdoc.swagger-ui.public-access in application.yml, evaluated with no "
                        + "environment set. True here serves the whole OpenAPI document to an "
                        + "unauthenticated caller on every ingress that does not separately block "
                        + "it. Open it per environment with SWAGGER_PUBLIC_ACCESS=true instead.")
                .isFalse();
    }

    @Test
    @DisplayName("health details are withheld from an anonymous caller unless an environment asks")
    void healthDetailsAreNotAlwaysShownByDefault() {
        String shipped = shippedValue("management", "endpoint", "health", "show-details");

        assertThat(resolveWithNothingSet(shipped))
                .as("management.endpoint.health.show-details in application.yml, evaluated with no "
                        + "environment set. /actuator is permitAll so the kubelet probes and the "
                        + "compose healthcheck can reach it, which means \"always\" hands every "
                        + "component's detail to anyone who can reach the port.")
                .isNotEqualTo("always");
    }

    private static String resolveWithNothingSet(String value) {
        return UNSET_ENVIRONMENT.replacePlaceholders(value, name -> null);
    }

    @SuppressWarnings("unchecked")
    private static String shippedValue(String... path) {
        try (InputStream in = ShippedConfigurationDefaultTest.class
                .getClassLoader().getResourceAsStream("application.yml")) {
            assertThat(in).as("application.yml on the classpath").isNotNull();

            Object node = new Yaml().load(in);
            for (String key : path) {
                if (!(node instanceof Map<?, ?> map) || !map.containsKey(key)) {
                    return fail("application.yml has no " + String.join(".", path));
                }
                node = ((Map<String, Object>) map).get(key);
            }
            return String.valueOf(node);
        } catch (Exception e) {
            return fail("could not read application.yml", e);
        }
    }
}
