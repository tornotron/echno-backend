package org.tornotron.echno_backend.common.identity.fake;

import org.springframework.lang.Nullable;
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

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An identity provider held in memory, for tests and local runs. It has no optional extras, so code
 * running on it sees what it would see on a provider that only has the port.
 *
 * <p>The methods below the port are what the real thing does on its own: a person accepting an
 * invitation, a link running out, an email arriving. Tests use them to drive and read that side. Like
 * a real provider, {@link #acceptInvitation} accepts any address it is given, because refusing a
 * different address is Echno's check.
 */
public class FakeIdentityProvider implements IdentityProvider {

    private record Person(String email, boolean emailVerified, String name) {
    }

    private record Invitation(String email, EchnoOrgId organization, InvitationStatus status) {
        Invitation with(InvitationStatus next) {
            return new Invitation(email, organization, next);
        }
    }

    private final Map<EchnoOrgId, ExternalOrgId> organizations = new ConcurrentHashMap<>();
    private final Map<ExternalUserId, Person> people = new ConcurrentHashMap<>();
    private final Map<EchnoOrgId, Set<ExternalUserId>> members = new ConcurrentHashMap<>();
    private final Map<ExternalInvitationId, Invitation> invitations = new ConcurrentHashMap<>();
    private final Map<String, Integer> invitationEmails = new ConcurrentHashMap<>();
    private final Map<ExternalUserId, Integer> passwordResetEmails = new ConcurrentHashMap<>();
    private final Set<ExternalUserId> sessions = ConcurrentHashMap.newKeySet();

    @Override
    public ExternalOrgId createOrganization(EchnoOrgId organization, String name) {
        ExternalOrgId id = new ExternalOrgId("org_" + UUID.randomUUID());
        if (organizations.putIfAbsent(organization, id) != null) {
            throw new IllegalStateException("Organization " + organization.value() + " already exists");
        }
        return id;
    }

    @Override
    public void deleteOrganization(EchnoOrgId organization) {
        organizations.remove(organization);
        members.remove(organization);
    }

    @Override
    public void addMember(ExternalUserId user, EchnoOrgId organization) {
        requireOrganization(organization);
        members.computeIfAbsent(organization, ignored -> ConcurrentHashMap.newKeySet()).add(user);
    }

    @Override
    public void removeMember(ExternalUserId user, EchnoOrgId organization) {
        Set<ExternalUserId> current = members.get(organization);
        if (current != null) {
            current.remove(user);
        }
    }

    @Override
    public ExternalInvitationId invite(String email, EchnoOrgId organization, @Nullable ExternalUserId inviter,
                                       Duration expiry) {
        requireOrganization(organization);
        ExternalInvitationId id = new ExternalInvitationId("inv_" + UUID.randomUUID());
        invitations.put(id, new Invitation(email, organization, InvitationStatus.of(InvitationState.PENDING)));
        recordInvitationEmail(email);
        return id;
    }

    @Override
    public void resendInvitation(ExternalInvitationId invitation) {
        recordInvitationEmail(pending(invitation).email());
    }

    @Override
    public void revokeInvitation(ExternalInvitationId invitation) {
        invitations.put(invitation, pending(invitation).with(InvitationStatus.of(InvitationState.REVOKED)));
    }

    @Override
    public Optional<InvitationStatus> invitationState(ExternalInvitationId invitation) {
        return Optional.ofNullable(invitations.get(invitation)).map(Invitation::status);
    }

    @Override
    public Optional<UserProfile> profile(ExternalUserId user) {
        return Optional.ofNullable(people.get(user))
                .map(person -> new UserProfile(person.email(), person.emailVerified(), person.name()));
    }

    @Override
    public void sendPasswordReset(ExternalUserId user) {
        if (!people.containsKey(user)) {
            throw new IllegalArgumentException("No such user: " + user.value());
        }
        passwordResetEmails.merge(user, 1, Integer::sum);
    }

    @Override
    public void endSessions(ExternalUserId user) {
        sessions.remove(user);
    }

    @Override
    public IdentityCapabilities capabilities() {
        return IdentityCapabilities.none();
    }

    /** Adds an existing account, as if the person had signed up earlier. */
    public ExternalUserId addUser(String email, boolean emailVerified, String name) {
        ExternalUserId id = new ExternalUserId("user_" + UUID.randomUUID());
        people.put(id, new Person(email, emailVerified, name));
        return id;
    }

    /**
     * Accepts an invitation as the person with this address, creating their account if they have none.
     * The address does not have to be the invited one, and it arrives verified.
     */
    public ExternalUserId acceptInvitation(ExternalInvitationId invitation, String email) {
        Invitation current = pending(invitation);
        ExternalUserId user = findUserByEmail(email).orElseGet(() -> addUser(email, true, email));
        invitations.put(invitation, current.with(InvitationStatus.acceptedBy(user)));
        return user;
    }

    /** Lets the invitation's link run out. */
    public void expireInvitation(ExternalInvitationId invitation) {
        invitations.put(invitation, pending(invitation).with(InvitationStatus.of(InvitationState.EXPIRED)));
    }

    public Optional<ExternalUserId> findUserByEmail(String email) {
        return people.entrySet().stream()
                .filter(entry -> entry.getValue().email().equalsIgnoreCase(email))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    public boolean isMember(ExternalUserId user, EchnoOrgId organization) {
        return members.getOrDefault(organization, Set.of()).contains(user);
    }

    public int invitationEmailsTo(String email) {
        return invitationEmails.getOrDefault(normalized(email), 0);
    }

    public int passwordResetEmailsTo(ExternalUserId user) {
        return passwordResetEmails.getOrDefault(user, 0);
    }

    public void startSession(ExternalUserId user) {
        sessions.add(user);
    }

    public boolean hasSession(ExternalUserId user) {
        return sessions.contains(user);
    }

    private Invitation pending(ExternalInvitationId invitation) {
        Invitation current = invitations.get(invitation);
        if (current == null) {
            throw new IllegalArgumentException("No such invitation: " + invitation.value());
        }
        if (current.status().state() != InvitationState.PENDING) {
            throw new InvitationNotPendingException(invitation, current.status().state());
        }
        return current;
    }

    private void requireOrganization(EchnoOrgId organization) {
        if (!organizations.containsKey(organization)) {
            throw new IllegalArgumentException("No such organization: " + organization.value());
        }
    }

    private void recordInvitationEmail(String email) {
        invitationEmails.merge(normalized(email), 1, Integer::sum);
    }

    private static String normalized(String email) {
        return email.toLowerCase(Locale.ROOT);
    }
}
