package org.tornotron.echno_backend.common.module;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the permissive {@link DefaultModuleEntitlementResolver} when nothing else provides a
 * {@link ModuleEntitlementResolver}.
 *
 * <p>This is an auto-configuration rather than a plain {@code @Configuration} on purpose: Spring
 * evaluates auto-configurations after every user bean definition is registered, so
 * {@code @ConditionalOnMissingBean} sees the billing implementation whether it is a scanned
 * component or a {@code @Bean} method. In a scanned {@code @Configuration} the same condition
 * would depend on scan order.
 */
@AutoConfiguration
public class ModuleEntitlementAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ModuleEntitlementResolver.class)
    ModuleEntitlementResolver defaultModuleEntitlementResolver() {
        return new DefaultModuleEntitlementResolver();
    }
}
