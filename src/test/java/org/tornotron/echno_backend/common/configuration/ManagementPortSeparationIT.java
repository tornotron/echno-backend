package org.tornotron.echno_backend.common.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that setting {@code management.server.port} to something other than {@code server.port}
 * moves the whole actuator surface onto the management port and leaves nothing behind on the port
 * the application serves the API on.
 *
 * <p>That split is the only control available for this exposure. {@code /actuator/**} is
 * {@code permitAll} in {@link SecurityConfig} because the kubelet readiness, liveness and startup
 * probes and the compose healthcheck all reach it without a credential, so the application cannot
 * authenticate it, and the k8s ingress controller ships with snippet annotations disabled, so the
 * ingress cannot 404 it either. What closes it is that the Service and the Ingress publish
 * {@code server.port} alone: with actuator on a second port, an external request has no route to
 * it, while a probe addressing the pod by port number still does. See echno-backend#571.
 *
 * <p>Two things are asserted, and both matter. That the management port still answers
 * {@code /actuator/health/liveness} is what says the probes survive the move, which is the way this
 * change can take a staging down: a pod whose probes point at a port nothing serves fails them
 * forever and is restarted forever. That the same paths no longer answer on the server port is the
 * fix itself; without it the change is cosmetic and actuator is still on the routed port.
 *
 * <p>The port is 0 here rather than 8081 so the test binds a free one and never collides with
 * whatever is running on the machine. The deployed environments set the real number through
 * {@code MANAGEMENT_SERVER_PORT}. {@code application.yml} sets no value at all, because setting one
 * is what makes Spring Boot start a second web server and unset is the only value that follows
 * {@code server.port}, so this test is the only place the split is exercised.
 *
 * <p>The context is closed after the class because it holds a second web server, which is worth
 * releasing rather than keeping in the shared context cache for the rest of the run.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        // The kubelet probes address /actuator/health/liveness and /actuator/health/readiness, and
        // those two group endpoints exist only when the health probes are enabled. Spring Boot turns
        // them on by itself when it detects it is running on Kubernetes, which is why they answer on
        // the cluster and not in a plain JVM. Enabled here so the test exercises the paths the probes
        // actually use rather than a shape only this JVM has.
        "management.endpoint.health.probes.enabled=true",
        "BACKEND_API_VERSION=v1",
        "DIGITAL_OCEAN_SPACES_URI=http://localhost:9000",
        "DIGITAL_OCEAN_SPACES_KEY_ID=test",
        "DIGITAL_OCEAN_SPACES_KEY_SECRET=test",
        "DIGITAL_OCEAN_SPACES_BUCKET_NAME=test-bucket",
        "DIGITAL_OCEAN_SPACES_CDN_ENDPOINT=http://localhost:9000",
        "ENCRYPTION_SECRET_KEY=0123456789abcdef0123456789abcdef",
        "KEYCLOAK_BACKEND_ADMIN_EMAIL=admin@test.local",
        "KEYCLOAK_BACKEND_ADMIN_FIRST_NAME=Test",
        "KEYCLOAK_BACKEND_ADMIN_LAST_NAME=Admin",
        "KEYCLOAK_BACKEND_ADMIN_PASSWORD=pw",
        "KEYCLOAK_BACKEND_ADMIN_USERNAME=admin",
        "KEYCLOAK_BACKEND_CLIENT=backend",
        "KEYCLOAK_BACKEND_REDIRECT_URI=http://localhost/*",
        "KEYCLOAK_BACKEND_REDIRECT_URI_APIDOG=http://localhost/webjars/*",
        "KEYCLOAK_BACKEND_SECRET=secret",
        "KEYCLOAK_BACKEND_SERVICE_EMAIL=svc@test.local",
        "KEYCLOAK_BACKEND_SERVICE_FIRST_NAME=Test",
        "KEYCLOAK_BACKEND_SERVICE_LAST_NAME=Service",
        "KEYCLOAK_BACKEND_SERVICE_PASSWORD=pw",
        "KEYCLOAK_BACKEND_SERVICE_USERNAME=svc",
        "KEYCLOAK_BACKEND_WEB_ORIGIN=http://localhost",
        "KEYCLOAK_FRONTEND_CLIENT=frontend",
        "KEYCLOAK_FRONTEND_REDIRECT_URI=http://localhost/*",
        "KEYCLOAK_FRONTEND_WEB_ORIGIN=http://localhost",
        "KEYCLOAK_INITIALIZER_APPLICATION_REALM=echno-realm",
        "KEYCLOAK_INITIALIZER_CLIENT_ID=admin-cli",
        "KEYCLOAK_INITIALIZER_MASTER_REALM=master",
        "KEYCLOAK_INITIALIZER_PASSWORD=pw",
        "KEYCLOAK_INITIALIZER_URL=http://localhost:0",
        "KEYCLOAK_INITIALIZER_USERNAME=admin",
        "SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_ISSUER_URI=http://localhost:0/realms/echno-realm"
})
class ManagementPortSeparationIT extends AbstractIntegrationTest {

    @LocalServerPort
    int serverPort;

    @LocalManagementPort
    int managementPort;

    @Autowired
    TestRestTemplate rest;

    @Test
    void managementPortIsNotTheServerPort() {
        assertThat(managementPort).isNotEqualTo(serverPort);
    }

    /**
     * All three paths, because two different consumers use two different ones: the kubelet probes
     * address the liveness and readiness groups, and the compose healthcheck greps the aggregate
     * {@code /actuator/health} for UP.
     */
    @Test
    void probesAndHealthcheckStillReachHealthOnTheManagementPort() {
        for (String path : new String[]{"/actuator/health", "/actuator/health/liveness",
                "/actuator/health/readiness"}) {
            ResponseEntity<String> response = rest.getForEntity(managementUrl(path), String.class);

            assertThat(response.getStatusCode()).as("%s", path).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).as("%s", path).contains("UP");
        }
    }

    @Test
    void metricsStillReachPrometheusOnTheManagementPort() {
        ResponseEntity<String> metrics =
                rest.getForEntity(managementUrl("/actuator/prometheus"), String.class);

        assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metrics.getBody()).contains("jvm_memory_used_bytes");
    }

    /**
     * The status is asserted as "not a success" rather than as one exact code on purpose. Once
     * actuator has moved, {@code /actuator/**} on the server port matches no handler, and this
     * application answers an unmatched path under a permitAll prefix with 500 and the body
     * {@code No static resource actuator/...}: the global handler treats Spring's
     * {@code NoResourceFoundException} as unexpected rather than mapping it to 404. That is worth
     * tidying separately, and pinning this test to 500 would make the tidy-up look like a
     * regression. What this test is for is the security property, which is that no actuator payload
     * comes back, so the body is checked too and not only the code.
     */
    @Test
    void nothingActuatorAnswersOnTheServerPort() {
        for (String path : new String[]{"/actuator", "/actuator/health", "/actuator/health/liveness",
                "/actuator/info", "/actuator/prometheus"}) {
            ResponseEntity<String> response =
                    rest.getForEntity("http://localhost:" + serverPort + path, String.class);

            assertThat(response.getStatusCode().is2xxSuccessful())
                    .as("%s must not succeed on the server port", path)
                    .isFalse();
            assertThat(response.getBody())
                    .as("%s must return no actuator payload on the server port", path)
                    .doesNotContain("jvm_memory_used_bytes")
                    .doesNotContain("\"status\":\"UP\"")
                    .doesNotContain("_links");
        }
    }

    private String managementUrl(String path) {
        return "http://localhost:" + managementPort + path;
    }
}
