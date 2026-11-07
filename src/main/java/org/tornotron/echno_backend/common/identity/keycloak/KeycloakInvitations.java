package org.tornotron.echno_backend.common.identity.keycloak;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.identity.ConditionalOnKeycloak;
import org.tornotron.echno_backend.common.identity.EchnoOrgId;
import org.tornotron.echno_backend.common.identity.ExternalInvitationId;
import org.tornotron.echno_backend.common.identity.ExternalUserId;
import org.tornotron.echno_backend.common.identity.InvitationNotPendingException;
import org.tornotron.echno_backend.common.identity.InvitationState;
import org.tornotron.echno_backend.common.identity.InvitationStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Invitations on Keycloak, which has no such thing: the account is created up front and Keycloak's
 * built-in email asks the person to fill in their profile and set a password. That email needs the
 * realm's SMTP settings, and without them {@link #invite} fails and leaves nothing behind.
 *
 * <p>An invitation has to remember a state the account cannot hold, because one person can be invited
 * to several organizations. Each is a childless group under {@value #PARENT_GROUP}, with the details
 * as group attributes: user attributes would need the realm's user profile changed. Nobody is ever a
 * member of these groups, so they never reach a token, and the parent appears only once an environment
 * sends its first invitation.
 *
 * <p>The state is read from the account. A person has accepted once the required actions the invitation
 * added are gone. A revoked invitation deletes the account only if it created that account.
 */
@Slf4j
@Service
@ConditionalOnKeycloak
class KeycloakInvitations {

    static final String PARENT_GROUP = "echno-invitations";
    static final String EXPIRES_AT = "expiresAt";

    private static final String EMAIL = "email";
    private static final String ORGANIZATION = "organizationId";
    private static final String USER = "userId";
    private static final String INVITER = "inviterId";
    private static final String ACTIONS = "requiredActions";
    private static final String CREATED_ACCOUNT = "createdAccount";
    private static final String REVOKED = "revoked";

    private static final String UPDATE_PROFILE = "UPDATE_PROFILE";
    private static final String UPDATE_PASSWORD = "UPDATE_PASSWORD";

    private final KeycloakGroupService groups;

    KeycloakInvitations(KeycloakGroupService groups) {
        this.groups = groups;
    }

    ExternalInvitationId invite(String email, EchnoOrgId organization, @Nullable ExternalUserId inviter,
                                Duration expiry) {
        try (Keycloak keycloak = groups.adminClient()) {
            RealmResource realm = keycloak.realm(groups.realm());
            if (groups.findOrgGroupId(keycloak, Long.toString(organization.value())) == null) {
                throw new IllegalArgumentException("Cannot invite to organization " + organization.value()
                        + ": its group was not found in Keycloak");
            }

            Optional<UserRepresentation> existing = realm.users().searchByEmail(email, true).stream().findFirst();
            boolean createAccount = existing.isEmpty();
            List<String> actions = createAccount ? List.of(UPDATE_PROFILE, UPDATE_PASSWORD) : List.of(UPDATE_PROFILE);
            String userId = createAccount ? createAccount(realm, email, actions) : existing.get().getId();

            String invitationId = null;
            try {
                invitationId = createRecord(realm, email, organization, userId, inviter, actions, createAccount,
                        Instant.now().plus(expiry));
                realm.users().get(userId).executeActionsEmail(actions, Math.toIntExact(expiry.toSeconds()));
                if (!createAccount) {
                    // Only once the email is out, so a failed invitation leaves the person's own account as it was.
                    requireActions(realm, userId, actions);
                }
                log.info("Sent an invitation to organization {} for user {}", organization.value(), userId);
                return new ExternalInvitationId(invitationId);
            } catch (RuntimeException e) {
                undo(realm, invitationId, createAccount ? userId : null);
                throw new IllegalStateException("Could not send the Keycloak invitation email for organization "
                        + organization.value() + ". Does the realm have SMTP settings? " + e.getMessage(), e);
            }
        }
    }

    void resend(ExternalInvitationId invitation) {
        try (Keycloak keycloak = groups.adminClient()) {
            RealmResource realm = keycloak.realm(groups.realm());
            Map<String, String> record = requirePending(realm, invitation);
            long remaining = Instant.parse(record.get(EXPIRES_AT)).getEpochSecond() - Instant.now().getEpochSecond();
            realm.users().get(record.get(USER))
                    .executeActionsEmail(actions(record), Math.toIntExact(Math.max(remaining, 1)));
        }
    }

    void revoke(ExternalInvitationId invitation) {
        try (Keycloak keycloak = groups.adminClient()) {
            RealmResource realm = keycloak.realm(groups.realm());
            Map<String, String> record = requirePending(realm, invitation);

            GroupRepresentation group = realm.groups().group(invitation.value()).toRepresentation();
            group.singleAttribute(REVOKED, "true");
            realm.groups().group(invitation.value()).update(group);

            // Deleting the account is also what stops a link already sent from working. An account
            // that existed before the invitation is the person's own, so it is left alone.
            if (Boolean.parseBoolean(record.get(CREATED_ACCOUNT))) {
                realm.users().get(record.get(USER)).remove();
            }
        }
    }

    Optional<InvitationStatus> state(ExternalInvitationId invitation) {
        try (Keycloak keycloak = groups.adminClient()) {
            RealmResource realm = keycloak.realm(groups.realm());
            return record(realm, invitation).map(record -> statusOf(realm, record));
        }
    }

    private Map<String, String> requirePending(RealmResource realm, ExternalInvitationId invitation) {
        Map<String, String> record = record(realm, invitation)
                .orElseThrow(() -> new IllegalArgumentException("No such invitation: " + invitation.value()));
        InvitationState state = statusOf(realm, record).state();
        if (state != InvitationState.PENDING) {
            throw new InvitationNotPendingException(invitation, state);
        }
        return record;
    }

    private static InvitationStatus statusOf(RealmResource realm, Map<String, String> record) {
        if (Boolean.parseBoolean(record.get(REVOKED))) {
            return InvitationStatus.of(InvitationState.REVOKED);
        }
        List<String> stillRequired;
        try {
            List<String> required = realm.users().get(record.get(USER)).toRepresentation().getRequiredActions();
            stillRequired = required == null ? List.of() : required;
        } catch (NotFoundException accountGone) {
            // Another invitation to this address was revoked, and that took the shared account with it.
            return InvitationStatus.of(InvitationState.REVOKED);
        }
        if (actions(record).stream().noneMatch(stillRequired::contains)) {
            return InvitationStatus.acceptedBy(new ExternalUserId(record.get(USER)));
        }
        return Instant.now().isAfter(Instant.parse(record.get(EXPIRES_AT)))
                ? InvitationStatus.of(InvitationState.EXPIRED)
                : InvitationStatus.of(InvitationState.PENDING);
    }

    /** The invitation's attributes, or empty when the id is unknown or names any other kind of group. */
    private static Optional<Map<String, String>> record(RealmResource realm, ExternalInvitationId invitation) {
        GroupRepresentation group;
        try {
            group = realm.groups().group(invitation.value()).toRepresentation();
        } catch (NotFoundException unknown) {
            return Optional.empty();
        }
        if (group.getPath() == null || !group.getPath().startsWith("/" + PARENT_GROUP + "/")) {
            return Optional.empty();
        }
        Map<String, String> flat = new HashMap<>();
        group.getAttributes().forEach((name, values) -> flat.put(name, values.getFirst()));
        return Optional.of(flat);
    }

    private static List<String> actions(Map<String, String> record) {
        return Arrays.asList(record.get(ACTIONS).split(","));
    }

    private static String createAccount(RealmResource realm, String email, List<String> actions) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(email);
        user.setEmail(email);
        user.setEnabled(true);
        user.setEmailVerified(false);
        user.setRequiredActions(new ArrayList<>(actions));
        try (Response response = realm.users().create(user)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private static void requireActions(RealmResource realm, String userId, List<String> actions) {
        UserResource resource = realm.users().get(userId);
        UserRepresentation user = resource.toRepresentation();
        List<String> required = user.getRequiredActions() == null
                ? new ArrayList<>() : new ArrayList<>(user.getRequiredActions());
        actions.stream().filter(action -> !required.contains(action)).forEach(required::add);
        user.setRequiredActions(required);
        resource.update(user);
    }

    private static String createRecord(RealmResource realm, String email, EchnoOrgId organization, String userId,
                                       @Nullable ExternalUserId inviter, List<String> actions,
                                       boolean createdAccount, Instant expiresAt) {
        GroupRepresentation group = new GroupRepresentation();
        group.setName("invitation-" + UUID.randomUUID());
        group.singleAttribute(EMAIL, email);
        group.singleAttribute(ORGANIZATION, Long.toString(organization.value()));
        group.singleAttribute(USER, userId);
        group.singleAttribute(ACTIONS, String.join(",", actions));
        group.singleAttribute(CREATED_ACCOUNT, Boolean.toString(createdAccount));
        group.singleAttribute(EXPIRES_AT, expiresAt.toString());
        if (inviter != null) {
            group.singleAttribute(INVITER, inviter.value());
        }
        try (Response response = realm.groups().group(parentGroupId(realm)).subGroup(group)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private static String parentGroupId(RealmResource realm) {
        return findParentGroupId(realm).orElseGet(() -> {
            GroupRepresentation parent = new GroupRepresentation();
            parent.setName(PARENT_GROUP);
            try (Response response = realm.groups().add(parent)) {
                if (response.getStatus() == Response.Status.CREATED.getStatusCode()) {
                    return CreatedResponseUtil.getCreatedId(response);
                }
            }
            // Two first invitations can race here, and the loser finds the winner's group.
            return findParentGroupId(realm)
                    .orElseThrow(() -> new IllegalStateException("Could not create the invitations group"));
        });
    }

    private static Optional<String> findParentGroupId(RealmResource realm) {
        return realm.groups().groups(PARENT_GROUP, true, 0, 1, true).stream()
                .findFirst()
                .map(GroupRepresentation::getId);
    }

    /** Takes back what a failed invitation wrote. A failure here must not hide the original one. */
    private static void undo(RealmResource realm, @Nullable String invitationId, @Nullable String createdUserId) {
        try {
            if (invitationId != null) {
                realm.groups().group(invitationId).remove();
            }
            if (createdUserId != null) {
                realm.users().get(createdUserId).remove();
            }
        } catch (RuntimeException e) {
            log.warn("Could not clean up after a failed invitation: {}", e.getMessage());
        }
    }
}
