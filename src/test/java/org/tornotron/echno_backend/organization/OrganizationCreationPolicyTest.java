package org.tornotron.echno_backend.organization;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;

class OrganizationCreationPolicyTest {

    private static final TestingAuthenticationToken ANYONE = signedIn();
    private static final TestingAuthenticationToken PLATFORM_ADMIN = signedIn(OrganizationCreationPolicy.PLATFORM_ADMIN);

    private static TestingAuthenticationToken signedIn(String... authorities) {
        TestingAuthenticationToken token = new TestingAuthenticationToken("someone", null, authorities);
        token.setAuthenticated(true);
        return token;
    }

    @Test
    void theStagingsKeepSelfServiceWithNoSetting() {
        OrganizationCreationPolicy policy = new OrganizationCreationPolicy("", "keycloak");

        assertThat(policy.selfService()).isTrue();
        assertThat(policy.allows(ANYONE)).isTrue();
    }

    @Test
    void underWorkOsOnlyAPlatformAdministratorStartsOne() {
        OrganizationCreationPolicy policy = new OrganizationCreationPolicy("", "workos");

        assertThat(policy.allows(ANYONE)).isFalse();
        assertThat(policy.allows(PLATFORM_ADMIN)).isTrue();
    }

    @Test
    void theSettingWinsOverTheProvider() {
        assertThat(new OrganizationCreationPolicy("false", "keycloak").allows(ANYONE)).isFalse();
        assertThat(new OrganizationCreationPolicy("true", "workos").allows(ANYONE)).isTrue();
    }

    @Test
    void nobodySignedOutStartsOne() {
        assertThat(new OrganizationCreationPolicy("true", "keycloak").allows(null)).isFalse();
    }
}
