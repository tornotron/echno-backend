package org.tornotron.echno_backend.common.module;

/**
 * The registry's only view of billing. Answers whether an organization holds the feature that
 * gates a module.
 *
 * <p>The default bean says yes to everything and is registered
 * {@code @ConditionalOnMissingBean}, so the billing implementation replaces it by existing. Keep
 * this signature stable: the registry, the default and the billing implementation all bind to it.
 */
public interface ModuleEntitlementResolver {

    boolean isEntitled(Long organizationId, String featureKey);
}
