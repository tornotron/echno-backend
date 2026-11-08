package org.tornotron.echno_backend.common.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * Two things from issue #828, held by one context.
 *
 * <p>The {@code local} profile binds. {@code application-local.yml} is the profile a developer
 * runs against {@code docker-compose.dev.yml}, and it is the one profile file in the repository
 * that no deployment ever loads, so nothing else would notice it going stale: a property renamed
 * in {@code application.yml} and not here would fail on the first {@code bootRun} of the next
 * newcomer. The context is booted with the profile active, over the Testcontainers database
 * (the {@code @DynamicPropertySource} outranks the profile file) and with the dev fixture
 * and the Redis provider switched off, since the fixture would write to the shared test
 * database and no Redis exists here. What
 * remains is every other value the profile sets, bound and resolved.
 *
 * <p>{@code GET /actuator/info} says what revision is running and nothing more. The endpoint is
 * {@code permitAll} with the rest of {@code /actuator}, so its content is the concern: the git
 * branch, sha and commit time and the build time must be there, and no key anywhere in the
 * document may name a datasource, a password, a secret or a key. The values come from
 * {@code git.properties} and {@code META-INF/build-info.properties}, both written by the Gradle
 * build, so the test also proves the build wires them onto the classpath.
 */
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "echno.dev-fixture.enabled=false",
        "echno.cache.provider=caffeine"
})
class ActuatorInfoIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private Environment environment;

    @Test
    void localProfileBindsTheDevStackValues() {
        assertThat(environment.getActiveProfiles()).contains("local");
        assertThat(environment.getProperty("echno.entitlement.mode")).isEqualTo("advisory");
        assertThat(environment.getProperty("spring.liquibase.contexts")).isEqualTo("v4");
        assertThat(environment.getProperty("echno.auth.jwt.jwk-set-uri"))
                .isEqualTo("http://localhost:8180/echno/jwks");
        assertThat(environment.getProperty("echno.auth.jwt.issuers")).isEqualTo("http://localhost:8180/echno");
        assertThat(environment.getProperty("echno.auth.jwt.audiences")).isEqualTo("echno-backend");
        assertThat(environment.getProperty("echno.cors.allowed-origins")).isEqualTo("http://localhost:3000");
        assertThat(environment.getProperty("digital-ocean.uri")).isEqualTo("http://localhost:9100");
        assertThat(environment.getProperty("digital-ocean.bucket-name")).isEqualTo("echno-dev");
        assertThat(environment.getProperty("spring.data.redis.port", Integer.class)).isEqualTo(6380);
        assertThat(environment.getProperty("echno.dev-fixture.subject")).isEqualTo("local-dev");
        // Resolved, not the raw ${...} placeholder application.yml carries.
        assertThat(environment.getProperty("API.VERSION")).isEqualTo("v1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void infoEndpointCarriesTheRevisionAndNothingSensitive() {
        ResponseEntity<Map> response = rest.getForEntity("/actuator/info", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();

        Map<String, Object> git = (Map<String, Object>) body.get("git");
        assertThat(git).as("git section from git.properties").isNotNull();
        assertThat(git.get("branch")).as("git.branch").isInstanceOf(String.class);
        Map<String, Object> commit = (Map<String, Object>) git.get("commit");
        assertThat(commit).as("git.commit").isNotNull();
        assertThat(commit.get("id")).as("git.commit.id").isNotNull();
        assertThat(commit.get("time")).as("git.commit.time").isNotNull();

        Map<String, Object> build = (Map<String, Object>) body.get("build");
        assertThat(build).as("build section from build-info.properties").isNotNull();
        assertThat(build.get("time")).as("build.time").isNotNull();
        assertThat(build.get("version")).as("build.version").isNotNull();

        assertThat(body).doesNotContainKeys("env", "java", "os");
        Deque<Map<String, Object>> pending = new ArrayDeque<>();
        pending.add(body);
        while (!pending.isEmpty()) {
            Map<String, Object> node = pending.pop();
            for (Map.Entry<String, Object> entry : node.entrySet()) {
                String key = entry.getKey().toLowerCase();
                assertThat(key)
                        .as("info key %s", entry.getKey())
                        .doesNotContain("datasource", "password", "secret", "credential", "token", "key");
                if (entry.getValue() instanceof Map<?, ?> child) {
                    pending.add((Map<String, Object>) child);
                }
            }
        }
    }
}
