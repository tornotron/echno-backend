package org.tornotron.echno_backend.common.identity.fake;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Selects the fake with {@code echno.identity.provider=fake}, and refuses it outside the test and
 * local profiles: it accepts anything and persists nothing, so a deployment must never run on it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "echno.identity.provider", havingValue = "fake")
public class FakeIdentityConfiguration {

    @Bean
    FakeIdentityProvider fakeIdentityProvider(Environment environment) {
        if (!environment.acceptsProfiles(Profiles.of("test", "local"))) {
            throw new IllegalStateException(
                    "echno.identity.provider=fake runs only under the test or local profile");
        }
        return new FakeIdentityProvider();
    }

    @Bean
    FakeIdentityTokenMapper fakeIdentityTokenMapper() {
        return new FakeIdentityTokenMapper();
    }
}
