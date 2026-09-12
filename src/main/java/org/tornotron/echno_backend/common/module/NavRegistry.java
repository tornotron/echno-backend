package org.tornotron.echno_backend.common.module;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects the navigation intents one module contributes through
 * {@link EchnoModule#registerNavigation(NavRegistry)}. One instance per module, owned by the
 * registry.
 */
public final class NavRegistry {

    private final List<NavDescriptor> descriptors = new ArrayList<>();

    public NavRegistry add(NavDescriptor descriptor) {
        descriptors.add(descriptor);
        return this;
    }

    public NavRegistry add(String label, String section, String path, String icon, List<String> requiredPermissions) {
        return add(new NavDescriptor(label, section, path, icon, requiredPermissions));
    }

    List<NavDescriptor> descriptors() {
        return List.copyOf(descriptors);
    }
}
