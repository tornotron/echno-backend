package org.tornotron.echno_backend.common.identity.workos;

import org.springframework.lang.Nullable;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.identity.EchnoOrgId;
import org.tornotron.echno_backend.common.identity.ExternalInvitationId;
import org.tornotron.echno_backend.common.identity.ExternalOrgId;
import org.tornotron.echno_backend.common.identity.ExternalUserId;
import org.tornotron.echno_backend.common.identity.IdentityCapabilities;
import org.tornotron.echno_backend.common.identity.IdentityProvider;
import org.tornotron.echno_backend.common.identity.InvitationNotPendingException;
import org.tornotron.echno_backend.common.identity.InvitationState;
import org.tornotron.echno_backend.common.identity.InvitationStatus;
import org.tornotron.echno_backend.common.identity.UserProfile;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The identity provider port on WorkOS User Management.
 *
 * <p>An organization is found by its {@code external_id}, which is the Echno organization id, so the
 * adapter keeps no mapping of its own and a retried create finds the first one. Every membership stays
 * on WorkOS's default role: Echno holds the roles.
 *
 * <p>What WorkOS does that the port does not say:
 * <ul>
 *   <li>Sending an invitation creates the WorkOS user at once, unverified, with a {@code pending}
 *       membership. Revoking it leaves both, so {@link #revokeInvitation} deletes the pending membership
 *       and leaves the user alone.</li>
 *   <li>Membership counts only when {@code active}; WorkOS lists only those unless asked.</li>
 *   <li>Resending keeps the expiry. Renewing an invitation is a revoke and a new invite.</li>
 *   <li>WorkOS sends the invitation, password reset and verification email itself, unless the
 *       environment has those emails switched off. Then {@link WorkOsSignInEmails}, reached through
 *       {@link #capabilities()}, gives Echno what it needs to send them.</li>
 * </ul>
 */
public class WorkOsIdentityProvider implements IdentityProvider {

    static final int MIN_EXPIRY_DAYS = 1;
    static final int MAX_EXPIRY_DAYS = 30;

    /** Refusals that mean the invitation stopped being pending. */
    private static final Set<String> NOT_PENDING = Set.of(
            "invite_accepted", "invite_revoked", "invite_expired", "invite_not_pending");

    /** How long a deletion, which WorkOS completes in the background, is waited for. */
    static final Duration DELETION_WAIT = Duration.ofSeconds(15);
    private static final Duration DELETION_POLL = Duration.ofMillis(500);

    private final WorkOsApi api;
    private final Clock clock;
    private final RetryAfterInterceptor.Pause pause;
    private final IdentityCapabilities capabilities;

    WorkOsIdentityProvider(WorkOsApi api, Clock clock, RetryAfterInterceptor.Pause pause) {
        this.api = api;
        this.clock = clock;
        this.pause = pause;
        this.capabilities = new IdentityCapabilities(Optional.empty(), Optional.empty(),
                Optional.of(new WorkOsSignInEmails(api)));
    }

    @Override
    public ExternalOrgId createOrganization(EchnoOrgId organization, String name) {
        String externalId = externalId(organization);
        try {
            return new ExternalOrgId(api.createOrganization(name, externalId).id());
        } catch (WorkOsApiException refused) {
            if (refused.is("external_id_already_used")) {
                return api.organizationByExternalId(externalId)
                        .map(existing -> new ExternalOrgId(existing.id()))
                        .orElseThrow(() -> refused);
            }
            throw refused;
        }
    }

    /**
     * WorkOS answers {@code 202} and deletes in the background, so this waits, a bounded time, until the
     * organization can no longer be found, and the port's promise that it is gone holds for the caller.
     */
    @Override
    public void deleteOrganization(EchnoOrgId organization) {
        String externalId = externalId(organization);
        Optional<WorkOsApi.Organization> existing = api.organizationByExternalId(externalId);
        if (existing.isEmpty()) {
            return;
        }
        api.deleteOrganization(existing.get().id());
        for (Duration waited = Duration.ZERO; waited.compareTo(DELETION_WAIT) < 0; waited = waited.plus(DELETION_POLL)) {
            if (api.organizationByExternalId(externalId).isEmpty()) {
                return;
            }
            try {
                pause.pause(DELETION_POLL);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new IllegalStateException("WorkOS had not finished deleting organization " + organization.value()
                + " after " + DELETION_WAIT.toSeconds() + " seconds");
    }

    @Override
    public void addMember(ExternalUserId user, EchnoOrgId organization) {
        api.createMembership(user.value(), requireOrganization(organization));
    }

    @Override
    public void removeMember(ExternalUserId user, EchnoOrgId organization) {
        api.organizationByExternalId(externalId(organization)).ifPresent(existing ->
                api.memberships(user.value(), existing.id(), "active", "inactive", "pending")
                        .forEach(membership -> api.deleteMembership(membership.id())));
    }

    @Override
    public ExternalInvitationId invite(String email, EchnoOrgId organization, @Nullable ExternalUserId inviter,
                                       Duration expiry) {
        int days = expiryDays(expiry);
        String organizationId = requireOrganization(organization);
        try {
            return new ExternalInvitationId(
                    api.invite(email, organizationId, inviter == null ? null : inviter.value(), days).id());
        } catch (WorkOsApiException refused) {
            if (refused.is("email_already_invited_to_organization")) {
                throw new DuplicateResourceException("This address already has a pending invitation to the organization");
            }
            throw refused;
        }
    }

    @Override
    public void resendInvitation(ExternalInvitationId invitation) {
        try {
            api.resendInvitation(invitation.value());
        } catch (WorkOsApiException refused) {
            throw notPendingOr(refused, invitation);
        }
    }

    @Override
    public void revokeInvitation(ExternalInvitationId invitation) {
        try {
            api.revokeInvitation(invitation.value());
        } catch (WorkOsApiException refused) {
            throw notPendingOr(refused, invitation);
        }
        api.invitation(invitation.value()).ifPresent(this::deletePendingMembership);
    }

    @Override
    public Optional<InvitationStatus> invitationState(ExternalInvitationId invitation) {
        return api.invitation(invitation.value()).map(this::status);
    }

    @Override
    public Optional<UserProfile> profile(ExternalUserId user) {
        return api.user(user.value()).map(found -> new UserProfile(found.email(), found.emailVerified(), name(found)));
    }

    @Override
    public void sendPasswordReset(ExternalUserId user) {
        WorkOsApi.User found = api.user(user.value())
                .orElseThrow(() -> new IllegalStateException("WorkOS has no user " + user.value()));
        api.sendPasswordReset(found.email());
    }

    @Override
    public void endSessions(ExternalUserId user) {
        api.sessions(user.value()).stream()
                .filter(session -> "active".equals(session.status()))
                .forEach(session -> api.revokeSession(session.id()));
    }

    @Override
    public IdentityCapabilities capabilities() {
        return capabilities;
    }

    // --- Helpers ---------------------------------------------------------------------------------

    static String externalId(EchnoOrgId organization) {
        return String.valueOf(organization.value());
    }

    private String requireOrganization(EchnoOrgId organization) {
        return api.organizationByExternalId(externalId(organization))
                .map(WorkOsApi.Organization::id)
                .orElseThrow(() -> new IllegalStateException(
                        "WorkOS has no organization for Echno organization " + organization.value()));
    }

    /** WorkOS takes whole days, 1 to 30. A part day counts as a day. */
    static int expiryDays(Duration expiry) {
        long days = (expiry.toHours() + 23) / 24;
        if (days < MIN_EXPIRY_DAYS || days > MAX_EXPIRY_DAYS) {
            throw new IllegalArgumentException("An invitation lasts 1 to 30 days, not " + expiry);
        }
        return (int) days;
    }

    private InvitationStatus status(WorkOsApi.Invitation invitation) {
        InvitationState state = switch (invitation.state()) {
            case "accepted" -> InvitationState.ACCEPTED;
            case "revoked" -> InvitationState.REVOKED;
            case "expired" -> InvitationState.EXPIRED;
            case "pending" -> invitation.expiresAt() != null && !invitation.expiresAt().isAfter(clock.instant())
                    ? InvitationState.EXPIRED
                    : InvitationState.PENDING;
            default -> throw new IllegalStateException("Unknown WorkOS invitation state " + invitation.state());
        };
        if (state == InvitationState.ACCEPTED && invitation.acceptedUserId() != null) {
            return InvitationStatus.acceptedBy(new ExternalUserId(invitation.acceptedUserId()));
        }
        return InvitationStatus.of(state);
    }

    private RuntimeException notPendingOr(WorkOsApiException refused, ExternalInvitationId invitation) {
        if (refused.code() != null && NOT_PENDING.contains(refused.code())) {
            InvitationState state = invitationState(invitation)
                    .map(InvitationStatus::state)
                    .orElse(InvitationState.REVOKED);
            return new InvitationNotPendingException(invitation, state);
        }
        return refused;
    }

    /** The pending membership a revoked invitation leaves behind. The user is left alone. */
    private void deletePendingMembership(WorkOsApi.Invitation invitation) {
        for (WorkOsApi.User user : api.usersByEmail(invitation.email())) {
            api.memberships(user.id(), invitation.organizationId(), "pending")
                    .forEach(membership -> api.deleteMembership(membership.id()));
        }
    }

    @Nullable
    private static String name(WorkOsApi.User user) {
        String joined = Stream.of(user.firstName(), user.lastName())
                .filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(" "));
        return joined.isBlank() ? null : joined;
    }
}
