package org.tornotron.echno_backend.common.module;

/**
 * The resolver in force when no billing implementation is on the context: every organization is
 * entitled to every feature. It makes the registry usable and testable on its own, and it is what
 * a build without the billing wiring runs with.
 */
public final class DefaultModuleEntitlementResolver implements ModuleEntitlementResolver {

    @Override
    public boolean isEntitled(Long organizationId, String featureKey) {
        return true;
    }
}
