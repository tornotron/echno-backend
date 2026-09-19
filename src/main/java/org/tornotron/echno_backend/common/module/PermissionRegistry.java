package org.tornotron.echno_backend.common.module;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects the permission keys one module contributes through
 * {@link EchnoModule#registerPermissions(PermissionRegistry)}. One instance per module, owned by
 * the registry. Keys follow the same {@code <module>:<action>} vocabulary as
 * {@link ModuleManifest#permissions()}.
 */
public final class PermissionRegistry {

    private final List<String> keys = new ArrayList<>();

    public PermissionRegistry add(String key) {
        keys.add(ModuleManifest.requirePermissionKey(key));
        return this;
    }

    List<String> keys() {
        return List.copyOf(keys);
    }
}
