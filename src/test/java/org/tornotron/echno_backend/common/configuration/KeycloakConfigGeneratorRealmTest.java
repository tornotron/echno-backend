package org.tornotron.echno_backend.common.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the generated realm file (no Spring context).
 *
 * <p>The realm template used to describe the backend client without a secret, so a realm created
 * from it was given a Keycloak-generated random one that no deployment could ever know. The
 * client_credentials grant KeycloakGroupService performs was therefore rejected from the first boot
 * of any rebuilt environment, and employee onboarding and org-role assignment failed for everyone.
 * The generated realm now carries the configured secret, so a fresh realm is born matching what the
 * deployment already holds.
 */
class KeycloakConfigGeneratorRealmTest {

    // Placeholder values only. Nothing here is, or resembles, a real credential.
    private static final String BACKEND_CLIENT_ID = "echno-backend-client";
    private static final String BACKEND_SECRET = "configured-value";

    @TempDir
    Path outputDir;

    private RealmRepresentation generateRealm() throws IOException {
        KeycloakConfigGenerator generator = new KeycloakConfigGenerator();
        ReflectionTestUtils.setField(generator, "clientId", BACKEND_CLIENT_ID);
        ReflectionTestUtils.setField(generator, "clientSecret", BACKEND_SECRET);
        ReflectionTestUtils.setField(generator, "redirectUri", "https://backend.example.test/*");
        ReflectionTestUtils.setField(generator, "webOrigin", "https://backend.example.test");
        ReflectionTestUtils.setField(generator, "frontendClientId", "echno-frontend-client");
        ReflectionTestUtils.setField(generator, "frontendRedirectUri", "https://ui.example.test/*");
        ReflectionTestUtils.setField(generator, "frontendWebOrigin", "https://ui.example.test");
        ReflectionTestUtils.setField(generator, "registrationAllowed", false);
        ReflectionTestUtils.setField(generator, "outputPath", outputDir.toString());

        ReflectionTestUtils.invokeMethod(generator, "generateRealmConfig");

        return new ObjectMapper().readValue(outputDir.resolve("init-keycloak.json").toFile(),
                RealmRepresentation.class);
    }

    @Test
    void generatedRealmGivesTheBackendClientTheConfiguredSecret() throws IOException {
        ClientRepresentation backend = generateRealm().getClients().stream()
                .filter(c -> BACKEND_CLIENT_ID.equals(c.getClientId()))
                .findFirst()
                .orElseThrow();

        assertThat(backend.getSecret()).isEqualTo(BACKEND_SECRET);
    }

    @Test
    void generatedRealmKeepsTheBackendClientCapabilities() throws IOException {
        ClientRepresentation backend = generateRealm().getClients().stream()
                .filter(c -> BACKEND_CLIENT_ID.equals(c.getClientId()))
                .findFirst()
                .orElseThrow();

        // Adding the secret must not disturb the two flags the client_credentials grant and the
        // authorization services depend on.
        assertThat(backend.isServiceAccountsEnabled()).isTrue();
        assertThat(backend.getAuthorizationServicesEnabled()).isTrue();
        assertThat(backend.isPublicClient()).isFalse();
    }

    @Test
    void generatedRealmLeavesNoPlaceholderUnsubstituted() throws IOException {
        generateRealm();

        String rendered = Files.readString(outputDir.resolve("init-keycloak.json"));

        assertThat(rendered).doesNotContain("${");
    }

    @Test
    void escapeJsonKeepsAValueWithQuotesOrBackslashesParseable() {
        assertThat(KeycloakConfigGenerator.escapeJson("a\"b\\c")).isEqualTo("a\\\"b\\\\c");
        assertThat(KeycloakConfigGenerator.escapeJson(null)).isEmpty();
        assertThat(KeycloakConfigGenerator.escapeJson("plain")).isEqualTo("plain");
    }
}
