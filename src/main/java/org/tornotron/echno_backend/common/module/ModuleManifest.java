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
 * @param permissions           the permission keys the module defines, in the
 *                              {@code <module>:<action>} form every {@code @PreAuthorize}
 *                              authority and the web nav gate already use, for example
 *                              {@code inspections:read}; a key that does not match
 *                              {@link #PERMISSION_KEY} fails construction, and so the boot
 * @param navDescriptors        the navigation intents the module publishes
 * @param enabledByDefault      for a module with no entitlement feature key, whether it is on for
 *                              every organization; ignored when a feature key is present, where
 *                              the resolver decides
 * @param core                  a module the application cannot run without, such as access
 *                              control: on for every organization, never paywalled, and without a
 *                              kill switch. It may depend only on other core modules, since a
 *                              dependency that can be switched off would switch it off too.
 */
public record ModuleManifest(
        String id,
        String name,
        String version,
        String entitlementFeatureKey,
        List<String> dependsOn,
        List<String> permissions,
        List<NavDescriptor> navDescriptors,
        boolean enabledByDefault,
        boolean core) {

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]*");

    /**
     * The permission vocabulary: {@code <module>:<action>}, lower-case letters, digits and
     * hyphens on both sides, exactly one colon. Shared with {@link PermissionRegistry}.
     */
    public static final Pattern PERMISSION_KEY = Pattern.compile("^[a-z][a-z0-9-]*:[a-z][a-z0-9-]*$");

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
        for (String key : permissions) {
            requirePermissionKey(key);
        }
        navDescriptors = navDescriptors == null ? List.of() : List.copyOf(navDescriptors);
        if (core && entitlementFeatureKey != null) {
            throw new IllegalArgumentException(
                    "Core module '" + id + "' cannot be gated by the billing feature " + entitlementFeatureKey);
        }
        if (core && !enabledByDefault) {
            throw new IllegalArgumentException("Core module '" + id + "' must be enabled by default");
        }
    }

    /** A feature module: the form every module that is not core is declared in. */
    public ModuleManifest(String id, String name, String version, String entitlementFeatureKey,
                          List<String> dependsOn, List<String> permissions,
                          List<NavDescriptor> navDescriptors, boolean enabledByDefault) {
        this(id, name, version, entitlementFeatureKey, dependsOn, permissions, navDescriptors,
                enabledByDefault, false);
    }

    /** A core module: on for every organization, free, and without a kill switch. */
    public static ModuleManifest core(String id, String name, String version, List<String> dependsOn,
                                      List<String> permissions, List<NavDescriptor> navDescriptors) {
        return new ModuleManifest(id, name, version, null, dependsOn, permissions, navDescriptors,
                true, true);
    }

    /**
     * Checks one permission key against {@link #PERMISSION_KEY}.
     *
     * @throws IllegalArgumentException when the key is blank or not in colon form
     */
    public static String requirePermissionKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A permission key must not be blank");
        }
        if (!PERMISSION_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "Permission key '" + key + "' must be <module>:<action>, matching " + PERMISSION_KEY.pattern());
        }
        return key;
    }

    /** Whether the module is gated by a billing feature. */
    public boolean isPaywalled() {
        return entitlementFeatureKey != null;
    }
}
