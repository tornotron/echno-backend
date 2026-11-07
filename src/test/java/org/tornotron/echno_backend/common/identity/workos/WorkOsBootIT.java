package org.tornotron.echno_backend.common.identity.workos;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.tornotron.echno_backend.common.identity.IdentityProvider;
import org.tornotron.echno_backend.common.identity.IdentityTokenMapper;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole application on WorkOS with no Keycloak setting, which is the shape of the first production
 * stack. The API address goes nowhere: nothing at boot calls WorkOS.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "echno.identity.provider=workos",
        "WORKOS_API_KEY=sk_test_boot_only",
        "WORKOS_CLIENT_ID=client_boot_only",
        "WORKOS_API_URL=http://127.0.0.1:1",
        "ECHNO_JWT_AUDIENCE=echno-api",
        "ECHNO_JWT_ACCEPTED_ISSUERS=https://api.workos.com/user_management/client_boot_only",
        "SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_JWK_SET_URI=",
        "ECHNO_CORS_ALLOWED_ORIGINS=https://ui.example.test",
        "BACKEND_API_VERSION=v1",
        "DIGITAL_OCEAN_SPACES_URI=http://localhost:9000",
        "DIGITAL_OCEAN_SPACES_KEY_ID=test",
        "DIGITAL_OCEAN_SPACES_KEY_SECRET=test",
        "DIGITAL_OCEAN_SPACES_BUCKET_NAME=test-bucket",
        "DIGITAL_OCEAN_SPACES_CDN_ENDPOINT=http://localhost:9000",
        "ENCRYPTION_SECRET_KEY=0123456789abcdef0123456789abcdef" // gitleaks:allow
})
class WorkOsBootIT extends AbstractIntegrationTest {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private TestRestTemplate rest;

    @Test
    void workOsIsTheProviderAndTheTokenSide() {
        assertThat(context.getBean(IdentityProvider.class)).isInstanceOf(WorkOsIdentityProvider.class);
        assertThat(context.getBean(IdentityTokenMapper.class)).isInstanceOf(WorkOsTokenMapper.class);
        assertThat(context.getBeansOfType(JwtDecoder.class)).hasSize(1);
        assertThat(context.getBean("workos")).isInstanceOf(WorkOsHealthIndicator.class);
    }

    @Test
    void noKeycloakBeanExists() {
        assertThat(Arrays.stream(context.getBeanDefinitionNames())
                .map(context::getType)
                .filter(type -> type != null && (type.getName().contains("Keycloak") || type.getName().contains("RPT"))))
                .isEmpty();
    }

    @Test
    void aBearerTokenThatIsNotOneIsRefused() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("not-a-token");

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/user/web", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
