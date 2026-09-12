package org.tornotron.echno_backend.common.module;

import org.tornotron.echno_backend.organization.Organization;

/**
 * The module service-provider interface. A module contributes exactly one Spring bean
 * implementing this, which is the single piece of boilerplate the registry needs to know the
 * module exists.
 *
 * <p>Only {@link #manifest()} is required. The lifecycle and registration hooks default to no-ops
 * so a module that has nothing to say on a surface writes nothing for it.
 */
public interface EchnoModule {

    /** The module's identity and contract with the registry. Must be stable across calls. */
    ModuleManifest manifest();

    /**
     * Called when the module becomes enabled for an organization. Reserved for the entitlement
     * change path; nothing in the registry calls it yet.
     */
    default void onEnable(Organization organization) {
    }

    /** Counterpart of {@link #onEnable(Organization)}. */
    default void onDisable(Organization organization) {
    }

    /**
     * Contributes navigation intents beyond those listed on the manifest. The registry merges the
     * two, so a module may declare its nav in either place.
     */
    default void registerNavigation(NavRegistry nav) {
    }

    /**
     * Contributes permission keys beyond those listed on the manifest. The registry merges the two.
     */
    default void registerPermissions(PermissionRegistry permissions) {
    }
}
