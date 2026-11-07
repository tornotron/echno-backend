package org.tornotron.echno_backend.common.identity;

import java.util.Optional;

/**
 * The optional extras one adapter offers beyond the port. Callers check for an extra here and work
 * without it when it is absent, so a feature only one provider has never widens the port.
 */
public record IdentityCapabilities(Optional<SelfRegistration> selfRegistration) {

    public static IdentityCapabilities none() {
        return new IdentityCapabilities(Optional.empty());
    }
}
