package org.tornotron.echno_backend.clientconfig;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientConfigSettingsTest {

    @Test
    void anEnvironmentThatSetsNothingIsKeycloakWithItsFrontendClientAndNoMinimum() {
        ClientConfigDto config = new ClientConfigSettings(new MockEnvironment()
                .withProperty("keycloak.frontend.client-id", "echno-frontend")).toDto();

        assertThat(config.provider()).isEqualTo("keycloak");
        assertThat(config.clientIds()).isEqualTo(new ClientConfigDto.ClientIds("echno-frontend", "echno-frontend", "echno-frontend"));
        assertThat(config.minimumVersions()).isEqualTo(new ClientConfigDto.MinimumVersions(null, null));
    }

    @Test
    void underWorkOsEachAppFallsBackToTheApplicationClientId() {
        ClientConfigDto config = new ClientConfigSettings(new MockEnvironment()
                .withProperty("echno.identity.provider", "workos")
                .withProperty("echno.identity.workos.client-id", "client_web")
                .withProperty("echno.client-config.android-client-id", "client_android")
                .withProperty("echno.client-config.ios-client-id", " ")
                .withProperty("echno.client-config.minimum-android-version", "2.4.0")
                .withProperty("echno.client-config.minimum-ios-version", "3")).toDto();

        assertThat(config.provider()).isEqualTo("workos");
        assertThat(config.clientIds()).isEqualTo(new ClientConfigDto.ClientIds("client_web", "client_android", "client_web"));
        assertThat(config.minimumVersions()).isEqualTo(new ClientConfigDto.MinimumVersions("2.4.0", "3"));
    }

    @Test
    void aMinimumThatIsNotAVersionRefusesToStart() {
        assertThatThrownBy(() -> new ClientConfigSettings(new MockEnvironment()
                .withProperty("echno.client-config.minimum-ios-version", "latest")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minimum-ios-version");
    }
}
