package org.tornotron.echno_backend.common.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.tornotron.echno_backend.common.identity.IdentityProvider;
import org.tornotron.echno_backend.common.identity.fake.FakeIdentityProvider;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole application with the fake provider and no Keycloak setting at all, which is the shape of a
 * stack that runs on another provider. The property list is what every provider needs. The one
 * Keycloak value the test classpath ships, for the web slices, is blanked here.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "echno.identity.provider=fake",
        "SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_JWK_SET_URI=",
        "ECHNO_CORS_ALLOWED_ORIGINS=https://ui.example.test",
        "BACKEND_API_VERSION=v1",
        "DIGITAL_OCEAN_SPACES_URI=http://localhost:9000",
        "DIGITAL_OCEAN_SPACES_KEY_ID=test",
        "DIGITAL_OCEAN_SPACES_KEY_SECRET=test",
        "DIGITAL_OCEAN_SPACES_BUCKET_NAME=test-bucket",
        "DIGITAL_OCEAN_SPACES_CDN_ENDPOINT=http://localhost:9000",
        "ENCRYPTION_SECRET_KEY=0123456789abcdef0123456789abcdef"
})
class NoKeycloakBootIT extends AbstractIntegrationTest {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private Environment environment;
    @Autowired
    private FilterChainProxy filterChainProxy;
    @Autowired
    private TestRestTemplate rest;

    @Test
    void noKeycloakSettingIsSet() {
        assertThat(environment.getProperty("keycloak-initializer.url")).isEmpty();
        assertThat(environment.getProperty("keycloak.secret")).isEmpty();
        assertThat(environment.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri")).isEmpty();
    }

    @Test
    void theFakeIsTheProvider() {
        assertThat(context.getBean(IdentityProvider.class)).isInstanceOf(FakeIdentityProvider.class);
    }

    @Test
    void noKeycloakBeanExists() {
        List<String> keycloakBeans = Arrays.stream(context.getBeanDefinitionNames())
                .filter(name -> {
                    Class<?> type = context.getType(name);
                    return type != null && isKeycloakOnly(type);
                })
                .toList();

        assertThat(keycloakBeans).isEmpty();
    }

    @Test
    void theSecurityChainHasNoTokenExchange() {
        assertThat(filterChainProxy.getFilterChains())
                .flatMap(chain -> chain.getFilters())
                .noneMatch(filter -> isKeycloakOnly(filter.getClass()));
    }

    @Test
    void aBearerTokenIsRefusedWithoutReachingForAnyProvider() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("not-a-token");

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/user/web", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anAppWithNoTokenReadsTheClientConfiguration() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/public/client-config", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"provider\":\"fake\"");
    }

    @Test
    void corsComesFromItsOwnSetting() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("https://ui.example.test");
        headers.setAccessControlRequestMethod(HttpMethod.GET);

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/user/web", HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);

        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo("https://ui.example.test");
    }

    private static boolean isKeycloakOnly(Class<?> type) {
        String name = type.getName();
        return name.startsWith("org.keycloak.") || name.contains("Keycloak") || name.contains("RPT");
    }
}
