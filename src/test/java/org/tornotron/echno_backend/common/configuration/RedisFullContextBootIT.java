package org.tornotron.echno_backend.common.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * Boots the whole application context with {@code echno.cache.provider=redis} against a real Redis
 * container, which the narrow {@link RedisDistributedStateIT} does not: that test constructs the
 * redis beans in isolation, so it cannot catch a failure that only appears when the full context
 * wires them alongside everything else.
 *
 * <p>Regression guard for the redis-provider boot: turning the provider on adds a
 * {@code RedisConnectionFactory}, which makes Spring Data Redis's repository auto-configuration
 * activate and register a {@code redisReferenceResolver} bean that depends on the {@code redisTemplate}
 * bean. That bean is absent because {@code RedisAutoConfiguration} is excluded, so the context fails to
 * start unless redis repositories are disabled (see {@code spring.data.redis.repositories.enabled} in
 * application.yml). This test deliberately does not set that property itself: it relies on the
 * application config, so removing the fix there breaks this test.
 *
 * <p>The properties below supply the environment the deployed app receives (object store, keycloak,
 * encryption) so the context reaches the redis wiring instead of failing earlier on an unresolved
 * placeholder. The keycloak initializer is an async {@code ApplicationReadyEvent} listener, so it runs
 * after refresh on another thread and does not affect this context-load assertion.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "echno.cache.provider=redis",
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
@Testcontainers
class RedisFullContextBootIT extends AbstractIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Test
    void contextLoads() {
    }
}
