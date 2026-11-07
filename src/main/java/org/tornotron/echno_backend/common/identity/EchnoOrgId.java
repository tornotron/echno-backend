package org.tornotron.echno_backend.common.identity;

/**
 * Echno's own id for an organization. Callers of the port name organizations by this id, so they
 * never hold a provider's id. Each adapter finds its own organization from it.
 */
public record EchnoOrgId(long value) {

    public EchnoOrgId {
        if (value <= 0) {
            throw new IllegalArgumentException("An organization id must be positive, not " + value);
        }
    }
}
