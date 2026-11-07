package org.tornotron.echno_backend.common.identity.workos;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class WorkOsSettingsValidatorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(WorkOsSettingsValidator.class)
            .withPropertyValues("echno.identity.provider=workos");

    private static String[] allSet() {
        return WorkOsSettingsValidator.REQUIRED.stream().map(name -> name + "=set").toArray(String[]::new);
    }

    @Test
    void withEverySettingPresentItStarts() {
        runner.withPropertyValues(allSet()).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void withNoAudienceItRefuses() {
        runner.withPropertyValues(allSet())
                .withPropertyValues("echno.security.jwt.audience=")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .hasMessageContaining("echno.security.jwt.audience")
                        .hasMessageNotContaining("accepted-issuers"));
    }

    @Test
    void anEmptyIssuerListRefuses() {
        runner.withPropertyValues(allSet())
                .withPropertyValues("echno.security.jwt.accepted-issuers=,")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("echno.security.jwt.accepted-issuers"));
    }

    @Test
    void itDoesNotRunUnderKeycloak() {
        new ApplicationContextRunner()
                .withUserConfiguration(WorkOsSettingsValidator.class)
                .run(context -> assertThat(context).hasNotFailed());
    }
}
