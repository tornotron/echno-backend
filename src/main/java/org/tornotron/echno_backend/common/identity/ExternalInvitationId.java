package org.tornotron.echno_backend.common.identity;

/** An invitation's id at the identity provider. */
public record ExternalInvitationId(String value) {

    public ExternalInvitationId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("An external invitation id cannot be blank");
        }
    }
}
