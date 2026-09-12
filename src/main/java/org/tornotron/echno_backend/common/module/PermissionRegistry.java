package org.tornotron.echno_backend.common.module;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects the permission keys one module contributes through
 * {@link EchnoModule#registerPermissions(PermissionRegistry)}. One instance per module, owned by
 * the registry.
 */
public final class PermissionRegistry {

    private final List<String> keys = new ArrayList<>();

    public PermissionRegistry add(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A permission key must not be blank");
        }
        keys.add(key);
        return this;
    }

    List<String> keys() {
        return List.copyOf(keys);
    }
}
