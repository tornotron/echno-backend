package org.tornotron.echno_backend.common.identity;

/** Where an invitation stands at the identity provider. */
public enum InvitationState {
    PENDING,
    ACCEPTED,
    EXPIRED,
    REVOKED
}
