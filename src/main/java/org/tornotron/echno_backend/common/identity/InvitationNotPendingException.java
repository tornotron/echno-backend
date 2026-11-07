package org.tornotron.echno_backend.common.identity;

/** Raised when an invitation is resent or revoked after it stopped being pending. */
public class InvitationNotPendingException extends RuntimeException {

    public InvitationNotPendingException(ExternalInvitationId invitation, InvitationState state) {
        super("Invitation " + invitation.value() + " is " + state + ", not pending");
    }
}
