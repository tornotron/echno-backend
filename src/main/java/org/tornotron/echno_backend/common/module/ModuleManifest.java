package org.tornotron.echno_backend.common.module;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A module's identity and its contract with the registry and the billing system.
 *
 * @param id                    stable machine id, for example {@code inspections}; namespaces the
 *                              package, nav, permissions, changelog and kill switch. Lower-case
 *                              letters, digits and hyphens only.
 * @param name                  human label
 * @param version               module semver, independent of the application version
 * @param entitlementFeatureKey the billing feature code that gates the module, for example
 *                              {@code MODULE_INSPECTIONS}; blank or null for a module that is not
 *                              paywalled
 * @param dependsOn             ids of modules that must be installed and enabled for this one to
 *                              function; validated at boot
 * @param permissions           the permission keys the module defines
 * @param navDescriptors        the navigation intents the module publishes
 * @param enabledByDefault      for a module with no entitlement feature key, whether it is on for
 *                              every organization; ignored when a feature key is present, where
 *                              the resolver decides
 */
public record ModuleManifest(
        String id,
        String name,
        String version,
        String entitlementFeatureKey,
        List<String> dependsOn,
        List<String> permissions,
        List<NavDescriptor> navDescriptors,
        boolean enabledByDefault) {

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]*");

    public ModuleManifest {
        Objects.requireNonNull(id, "id");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Module id '" + id + "' must match " + ID.pattern());
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        entitlementFeatureKey = entitlementFeatureKey == null || entitlementFeatureKey.isBlank()
                ? null : entitlementFeatureKey;
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        navDescriptors = navDescriptors == null ? List.of() : List.copyOf(navDescriptors);
    }

    /** Whether the module is gated by a billing feature. */
    public boolean isPaywalled() {
        return entitlementFeatureKey != null;
    }
}
