package org.tornotron.echno_backend.common.identity.keycloak;

import jakarta.ws.rs.NotFoundException;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.identity.ConditionalOnKeycloak;
import org.tornotron.echno_backend.common.identity.EchnoOrgId;
import org.tornotron.echno_backend.common.identity.ExternalInvitationId;
import org.tornotron.echno_backend.common.identity.ExternalOrgId;
import org.tornotron.echno_backend.common.identity.ExternalUserId;
import org.tornotron.echno_backend.common.identity.IdentityCapabilities;
import org.tornotron.echno_backend.common.identity.IdentityProvider;
import org.tornotron.echno_backend.common.identity.InvitationStatus;
import org.tornotron.echno_backend.common.identity.OrgRoleGroups;
import org.tornotron.echno_backend.common.identity.SelfRegistration;
import org.tornotron.echno_backend.common.identity.UserProfile;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Keycloak behind the port. An organization is the group {@code org-{id}}, and a member is someone in
 * that group, as {@link KeycloakGroupService} describes. Self-registration and role subgroups are the
 * extras only this adapter has.
 */
@Service
@ConditionalOnKeycloak
public class KeycloakIdentityProvider implements IdentityProvider {

    /** An organization's group is this and its id, which is what the adapter addresses it by. */
    static final String GROUP_PREFIX = "org-";

    private static final String UPDATE_PASSWORD = "UPDATE_PASSWORD";
    /** How long a password reset link stays usable: Keycloak's own default for such a link. */
    private static final int PASSWORD_RESET_LIFESPAN_SECONDS = 12 * 60 * 60;

    private final KeycloakGroupService groups;
    private final KeycloakInvitations invitations;
    private final IdentityCapabilities capabilities;

    public KeycloakIdentityProvider(KeycloakGroupService groups,
                                    KeycloakUserProvisioningService provisioning,
                                    KeycloakInvitations invitations) {
        this.groups = groups;
        this.invitations = invitations;
        this.capabilities = new IdentityCapabilities(
                Optional.of(new Registration(provisioning)),
                Optional.of(new RoleSubgroups(groups)));
    }

    @Override
    public ExternalOrgId createOrganization(EchnoOrgId organization, String name) {
        groups.createOrganizationGroup(key(organization), name);
        return new ExternalOrgId(GROUP_PREFIX + key(organization));
    }

    @Override
    public void deleteOrganization(EchnoOrgId organization) {
        groups.deleteOrganizationGroup(key(organization));
    }

    @Override
    public void addMember(ExternalUserId user, EchnoOrgId organization) {
        groups.addUserToOrganization(user.value(), key(organization));
    }

    @Override
    public void removeMember(ExternalUserId user, EchnoOrgId organization) {
        groups.removeUserFromOrganization(user.value(), key(organization));
    }

    @Override
    public ExternalInvitationId invite(String email, EchnoOrgId organization, @Nullable ExternalUserId inviter,
                                       Duration expiry) {
        return invitations.invite(email, organization, inviter, expiry);
    }

    @Override
    public void resendInvitation(ExternalInvitationId invitation) {
        invitations.resend(invitation);
    }

    @Override
    public void revokeInvitation(ExternalInvitationId invitation) {
        invitations.revoke(invitation);
    }

    @Override
    public Optional<InvitationStatus> invitationState(ExternalInvitationId invitation) {
        return invitations.state(invitation);
    }

    @Override
    public Optional<UserProfile> profile(ExternalUserId user) {
        try (Keycloak keycloak = groups.adminClient()) {
            UserRepresentation found = keycloak.realm(groups.realm()).users().get(user.value()).toRepresentation();
            return Optional.of(new UserProfile(found.getEmail(), Boolean.TRUE.equals(found.isEmailVerified()),
                    displayName(found)));
        } catch (NotFoundException unknown) {
            return Optional.empty();
        }
    }

    @Override
    public void sendPasswordReset(ExternalUserId user) {
        try (Keycloak keycloak = groups.adminClient()) {
            keycloak.realm(groups.realm()).users().get(user.value())
                    .executeActionsEmail(List.of(UPDATE_PASSWORD), PASSWORD_RESET_LIFESPAN_SECONDS);
        }
    }

    @Override
    public void endSessions(ExternalUserId user) {
        try (Keycloak keycloak = groups.adminClient()) {
            keycloak.realm(groups.realm()).users().get(user.value()).logout();
        }
    }

    @Override
    public IdentityCapabilities capabilities() {
        return capabilities;
    }

    private static String key(EchnoOrgId organization) {
        return Long.toString(organization.value());
    }

    private static String displayName(UserRepresentation user) {
        String name = Stream.of(user.getFirstName(), user.getLastName())
                .filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(" "));
        return name.isEmpty() ? user.getUsername() : name;
    }

    private record Registration(KeycloakUserProvisioningService provisioning) implements SelfRegistration {

        @Override
        public ExternalUserId createAccount(String username, String email, String password) {
            return new ExternalUserId(provisioning.createUser(username, email, password));
        }

        @Override
        public void deleteAccount(ExternalUserId user) {
            provisioning.deleteUser(user.value());
        }
    }

    private record RoleSubgroups(KeycloakGroupService groups) implements OrgRoleGroups {

        @Override
        public void assign(ExternalUserId user, EchnoOrgId organization, OrgRole role) {
            groups.assignOrgRole(user.value(), key(organization), role);
        }

        @Override
        public void remove(ExternalUserId user, EchnoOrgId organization, OrgRole role) {
            groups.removeOrgRole(user.value(), key(organization), role);
        }
    }
}
