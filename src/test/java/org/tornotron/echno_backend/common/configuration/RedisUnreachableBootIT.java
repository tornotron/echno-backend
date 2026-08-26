package org.tornotron.echno_backend.common.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * Boots the whole application context with {@code echno.cache.provider=redis} pointed at a Redis
 * that is not there.
 *
 * <p>This is the deployment reality, not a hypothetical. The compose staging sets
 * {@code ECHNO_CACHE_PROVIDER=redis} and {@code SPRING_DATA_REDIS_HOST=echno-redis} while the redis
 * container itself may not be running: the app deploy renders the backend environment, but only an
 * infra run creates that container, and the two are deployed by different playbook runs.
 *
 * <p>The cache and rate limiter tolerated that, because Lettuce connects lazily and neither touches
 * Redis until something asks it to. Chat real-time delivery does not: its listener container opens a
 * subscription while the context is refreshing. Without care that turns a missing broker into a
 * backend that never becomes healthy, which is a chat feature taking the whole API down with it.
 *
 * <p>So the rule this pins down: an unreachable broker degrades real-time delivery and nothing else.
 * The context must still load, the API must still serve, and chat falls back to the polling the web
 * client deliberately kept.
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
class RedisUnreachableBootIT extends AbstractIntegrationTest {

    // Port 1 is reserved and nothing listens on it, so every connection attempt is refused
    // immediately rather than hanging until a timeout.
    static {
        System.setProperty("spring.data.redis.host", "127.0.0.1");
        System.setProperty("spring.data.redis.port", "1");
    }

    @Test
    void contextLoadsWithoutAReachableRedis() {
    }
}
