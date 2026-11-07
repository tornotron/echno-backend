package org.tornotron.echno_backend.common.identity.keycloak;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.tornotron.echno_backend.EchnoBackendApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeycloakSettingsValidatorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KeycloakSettingsValidator.class);

    private static String[] allSet() {
        return KeycloakSettingsValidator.REQUIRED.stream().map(name -> name + "=set").toArray(String[]::new);
    }

    @Test
    void withEverySettingPresentItStarts() {
        runner.withPropertyValues(allSet()).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void withASettingMissingItNamesItAndRefuses() {
        runner.withPropertyValues(allSet())
                .withPropertyValues("keycloak.secret=")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .hasMessageContaining("keycloak.secret")
                        .hasMessageNotContaining("keycloak.issuer-uri"));
    }

    @Test
    void aSettingOfTwoBlankHalvesCountsAsBlank() {
        runner.withPropertyValues(allSet())
                .withPropertyValues("keycloak.redirect-uri=,")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("keycloak.redirect-uri"));
    }

    @Test
    void anOptionalSecondHalfMayBeBlank() {
        // KEYCLOAK_FRONTEND_WEB_ORIGIN_LOCALHOST is optional, and a staging's value arrives like this.
        runner.withPropertyValues(allSet())
                .withPropertyValues("keycloak.frontend.web-origin=https://ui.example.test,")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void itDoesNotRunUnderAnotherProvider() {
        runner.withPropertyValues("echno.identity.provider=fake")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * The whole application, with the shipped configuration and nothing set: what a production stack
     * gets if it forgets to name its provider. It stops here, before any database is reached.
     */
    @Test
    void theRealApplicationRefusesToStartWithNothingSet() {
        SpringApplicationBuilder application = new SpringApplicationBuilder(EchnoBackendApplication.class)
                .web(WebApplicationType.NONE);

        assertThatThrownBy(application::run)
                .hasMessageContaining("echno.identity.provider is keycloak")
                .hasMessageContaining("keycloak-initializer.url")
                .hasMessageContaining("keycloak.secret");
    }
}
