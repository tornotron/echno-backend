package org.tornotron.echno_backend.common.identity.fake;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.tornotron.echno_backend.common.identity.IdentityProvider;

import static org.assertj.core.api.Assertions.assertThat;

class FakeIdentityConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FakeIdentityConfiguration.class);

    @Test
    void itIsAbsentUnlessSelected() {
        runner.withPropertyValues("spring.profiles.active=test")
                .run(context -> assertThat(context).doesNotHaveBean(IdentityProvider.class));
        runner.withPropertyValues("spring.profiles.active=test", "echno.identity.provider=keycloak")
                .run(context -> assertThat(context).doesNotHaveBean(IdentityProvider.class));
    }

    @Test
    void itStartsUnderTheTestAndLocalProfiles() {
        for (String profile : new String[] {"test", "local"}) {
            runner.withPropertyValues("echno.identity.provider=fake", "spring.profiles.active=" + profile)
                    .run(context -> assertThat(context).hasSingleBean(IdentityProvider.class));
        }
    }

    @Test
    void itRefusesToStartAnywhereElse() {
        runner.withPropertyValues("echno.identity.provider=fake")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause()
                        .hasMessageContaining("only under the test or local profile"));
        runner.withPropertyValues("echno.identity.provider=fake", "spring.profiles.active=production")
                .run(context -> assertThat(context).hasFailed());
    }
}
