package org.tornotron.echno_backend.common.identity;

import org.springframework.lang.Nullable;

/**
 * An invitation's state, and who accepted it.
 *
 * <p>{@code acceptedUser} is present only when the state is {@link InvitationState#ACCEPTED}. It can
 * be a person whose address differs from the invited one, because a provider may let someone on the
 * same email domain accept. Comparing the two addresses is Echno's job.
 */
public record InvitationStatus(InvitationState state, @Nullable ExternalUserId acceptedUser) {

    public static InvitationStatus of(InvitationState state) {
        return new InvitationStatus(state, null);
    }

    public static InvitationStatus acceptedBy(ExternalUserId user) {
        return new InvitationStatus(InvitationState.ACCEPTED, user);
    }
}
