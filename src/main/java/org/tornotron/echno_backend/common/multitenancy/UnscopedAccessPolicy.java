package org.tornotron.echno_backend.common.multitenancy;

import java.util.Locale;

/**
 * What to do when work reaches the database with no tenant scope declared at all: no
 * organization id, no {@code @BypassTenantFilter}, no {@link WithoutTenant}.
 *
 * <p>The three values are the three stages of closing #507, and the property exists so an
 * environment can sit at a different stage from the shipped default and so the strict setting
 * can be stepped back during an incident without a code change.
 */
public enum UnscopedAccessPolicy {

    /** Proceed silently. The pre-#507 behaviour, kept only as the incident escape hatch. */
    ALLOW,

    /** Proceed, but count the event and log it, so the real call sites can be enumerated. */
    WARN,

    /** Refuse the work. */
    DENY;

    /**
     * Reads a configured value, accepting any case so an environment variable can be written
     * either way.
     *
     * @throws IllegalArgumentException naming the property and the accepted values, so a typo
     *                                  fails at startup rather than quietly selecting a default
     */
    static UnscopedAccessPolicy parse(String property, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    property + " must be one of ALLOW, WARN, DENY but was empty");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    property + " must be one of ALLOW, WARN, DENY but was '" + value + "'", e);
        }
    }
}
