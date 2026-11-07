package org.tornotron.echno_backend.common.identity.task;

import java.util.UUID;

/**
 * The provider calls for one invitation, made by {@link IdentityTaskWorker} after the change that asked
 * for them commits. Implemented beside the invitation records, which is where what to send is known.
 * Each call reads the invitation as it is when the call runs and is safe to repeat.
 */
public interface InvitationDelivery {

    /** Sends the invitation, unless it was sent already or is no longer open. */
    void send(UUID invitation);

    /** Sends the same link again. Sends it for the first time if it never went. */
    void resend(UUID invitation);

    /** Withdraws the link the provider sent and sends a new one with the invitation's current expiry. */
    void renew(UUID invitation);

    /** Stops the link the provider sent from working. */
    void revoke(UUID invitation);
}
