package org.tornotron.echno_backend.common.identity;

import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.Optional;

/**
 * What Echno asks of an identity provider, whichever one is running. The setting
 * {@code echno.identity.provider} picks the adapter.
 *
 * <p>The port holds only what every provider can do. Roles are not on it: the provider never stores
 * or returns them. Membership has no read, because Echno reads it from {@code Employee}. A feature
 * only one provider has is an extra under {@link #capabilities()}.
 *
 * <p>{@code IdentityProviderContract} pins the behaviour every adapter has to show. A failure at the
 * provider surfaces as an unchecked exception, and the caller decides whether it matters.
 */
public interface IdentityProvider {

    /** Creates the organization at the provider and returns the provider's id for it. */
    ExternalOrgId createOrganization(EchnoOrgId organization, String name);

    /** Removes the organization at the provider. Doing nothing is fine if it is not there. */
    void deleteOrganization(EchnoOrgId organization);

    /** Makes the person a member. Repeat-safe. Refused when the organization does not exist. */
    void addMember(ExternalUserId user, EchnoOrgId organization);

    /** Ends the person's membership. Doing nothing is fine if the organization is not there. */
    void removeMember(ExternalUserId user, EchnoOrgId organization);

    /**
     * Sends an invitation link to the address. The invitation alone does not make anyone a member.
     *
     * @param inviter who sent it, or null when nobody did (the first administrator of an organization).
     * @param expiry  how long the link stays usable.
     */
    ExternalInvitationId invite(String email, EchnoOrgId organization, @Nullable ExternalUserId inviter,
                                Duration expiry);

    /**
     * Sends the link again.
     *
     * @throws InvitationNotPendingException if it is no longer pending.
     */
    void resendInvitation(ExternalInvitationId invitation);

    /**
     * Stops the link working.
     *
     * @throws InvitationNotPendingException if it is no longer pending.
     */
    void revokeInvitation(ExternalInvitationId invitation);

    /** The invitation's state, or empty when the provider has no such invitation. */
    Optional<InvitationStatus> invitationState(ExternalInvitationId invitation);

    /** What the provider holds about the person, or empty when it has no such person. */
    Optional<UserProfile> profile(ExternalUserId user);

    /** Sends the person the provider's own password reset email. */
    void sendPasswordReset(ExternalUserId user);

    /** Ends the person's sessions, so no new token can come from them. */
    void endSessions(ExternalUserId user);

    /** The optional extras this adapter has. */
    IdentityCapabilities capabilities();
}
