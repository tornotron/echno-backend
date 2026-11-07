package org.tornotron.echno_backend.common.identity;

/** A person's id at the identity provider: the subject of their tokens, never an Echno user id. */
public record ExternalUserId(String value) {

    public ExternalUserId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("An external user id cannot be blank");
        }
    }
}
