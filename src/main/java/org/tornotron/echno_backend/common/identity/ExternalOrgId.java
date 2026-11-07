package org.tornotron.echno_backend.common.identity;

/** An organization's id at the identity provider, returned when it is created there. */
public record ExternalOrgId(String value) {

    public ExternalOrgId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("An external organization id cannot be blank");
        }
    }
}
