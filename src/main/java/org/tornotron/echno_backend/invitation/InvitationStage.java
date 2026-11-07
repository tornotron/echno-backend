package org.tornotron.echno_backend.invitation;

/** Where an Echno invitation stands. Only {@link #PENDING} can still be accepted, resent or revoked. */
public enum InvitationStage {
    /** Waiting for the person. The link may not have been sent yet: see {@code sentAt}. */
    PENDING,
    /** Accepted by the invited address, and the person is an employee. */
    ACCEPTED,
    /** Accepted at the provider by a different address, so nobody was linked. */
    ACCEPTED_BY_ANOTHER_ADDRESS,
    EXPIRED,
    REVOKED
}
