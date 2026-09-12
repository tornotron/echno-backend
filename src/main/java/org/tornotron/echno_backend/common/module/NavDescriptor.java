package org.tornotron.echno_backend.common.module;

import java.util.List;
import java.util.Objects;

/**
 * A navigation intent a module publishes. The backend does not render navigation; the web
 * loader maps a descriptor onto its own nav platform.
 *
 * @param label               human label of the entry
 * @param section             the nav section the entry belongs to, for example {@code "site"}
 * @param path                the route path the entry opens
 * @param icon                an icon key the web app resolves, may be null
 * @param requiredPermissions permission keys the caller must hold for the entry to show; empty
 *                            means visible to every member
 */
public record NavDescriptor(
        String label,
        String section,
        String path,
        String icon,
        List<String> requiredPermissions) {

    public NavDescriptor {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(path, "path");
        requiredPermissions = requiredPermissions == null ? List.of() : List.copyOf(requiredPermissions);
    }
}
