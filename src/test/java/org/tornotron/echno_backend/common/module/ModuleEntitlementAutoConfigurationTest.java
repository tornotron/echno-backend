package org.tornotron.echno_backend.common.module;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The default resolver is present when nothing else is, and steps aside when a real one is.
 * Driven through {@link ApplicationContextRunner} with only this auto-configuration, so it costs
 * no application context.
 */
class ModuleEntitlementAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ModuleEntitlementAutoConfiguration.class));

    @Test
    void registersThePermissiveDefaultWhenNoResolverIsPresent() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ModuleEntitlementResolver.class);
            assertThat(context.getBean(ModuleEntitlementResolver.class))
                    .isInstanceOf(DefaultModuleEntitlementResolver.class);
            assertThat(context.getBean(ModuleEntitlementResolver.class).isEntitled(1L, "ANY")).isTrue();
        });
    }

    @Test
    void givesWayToAUserProvidedResolver() {
        runner.withUserConfiguration(DenyingResolverConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(ModuleEntitlementResolver.class);
            assertThat(context.getBean(ModuleEntitlementResolver.class).isEntitled(1L, "ANY")).isFalse();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class DenyingResolverConfiguration {
        @Bean
        ModuleEntitlementResolver denyingResolver() {
            return (organizationId, featureKey) -> false;
        }
    }
}
