package org.tornotron.echno_backend.common.identity.task;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.identity.EchnoOrgId;
import org.tornotron.echno_backend.common.identity.ExternalUserId;

import java.util.UUID;

/**
 * Asks for a call to the identity provider once the caller's transaction commits. Echno's own change
 * takes effect at once, and nothing about the provider can fail or slow it; {@link IdentityTaskWorker}
 * makes the call afterwards. Rolled back with the caller's transaction, so a change that did not
 * happen asks for nothing.
 *
 * <p>Asking again replaces a request still waiting for the same thing, with no effect on any other. For
 * a role that is what keeps the order right: only the latest of a give and a take is ever sent.
 */
@Component
public class IdentityTaskQueue {

    enum Kind {
        REMOVE_MEMBER, END_SESSIONS, ASSIGN_ROLE, REMOVE_ROLE,
        SEND_INVITATION, RESEND_INVITATION, RENEW_INVITATION, REVOKE_INVITATION,
        CREATE_ORGANIZATION
    }

    private final JdbcTemplate jdbc;

    public IdentityTaskQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Ends the person's membership at the provider. */
    public void removeMember(ExternalUserId account, EchnoOrgId organization) {
        enqueue(Kind.REMOVE_MEMBER, "member:" + account.value() + ":" + organization.value(), account, null,
                organization.value(), null);
    }

    /** Ends the person's sessions, unless they have an active employee somewhere when the time comes. */
    public void endSessionsIfNoActiveEmployee(ExternalUserId account, Long userId) {
        enqueue(Kind.END_SESSIONS, "sessions:" + account.value(), account, userId, null, null);
    }

    /** Brings the provider's copy of one person's role, if it keeps one, in line with Echno. */
    public void recordRole(ExternalUserId account, EchnoOrgId organization, OrgRole role, boolean holdsIt) {
        enqueue(holdsIt ? Kind.ASSIGN_ROLE : Kind.REMOVE_ROLE,
                "role:" + account.value() + ":" + organization.value() + ":" + role.name(),
                account, null, organization.value(), role.name());
    }

    /**
     * Creates the organization at the provider and records which one it is. Asked for before the first
     * invitation to it, so the worker, which runs requests oldest first, makes it before the send.
     */
    public void createOrganization(EchnoOrgId organization) {
        insert(Kind.CREATE_ORGANIZATION, "organization:" + organization.value(), null, null, organization.value(),
                null, null);
    }

    /** Sends the invitation's link. */
    public void sendInvitation(UUID invitation) {
        enqueueInvitation(Kind.SEND_INVITATION, invitation);
    }

    /** Sends the same link again, keeping its expiry. */
    public void resendInvitation(UUID invitation) {
        enqueueInvitation(Kind.RESEND_INVITATION, invitation);
    }

    /** Replaces the link with a new one carrying the invitation's new expiry. */
    public void renewInvitation(UUID invitation) {
        enqueueInvitation(Kind.RENEW_INVITATION, invitation);
    }

    /** Stops the link working. Replaces a send still waiting, so a link revoked before it went never goes. */
    public void revokeInvitation(UUID invitation) {
        enqueueInvitation(Kind.REVOKE_INVITATION, invitation);
    }

    /** One pending request per invitation, so only the latest of a send, resend, renew or revoke is made. */
    private void enqueueInvitation(Kind kind, UUID invitation) {
        insert(kind, "invitation:" + invitation, null, null, null, null, invitation);
    }

    private void enqueue(Kind kind, String dedupeKey, ExternalUserId account, @Nullable Long userId,
                         @Nullable Long organizationId, @Nullable String role) {
        insert(kind, dedupeKey, account.value(), userId, organizationId, role, null);
    }

    private void insert(Kind kind, String dedupeKey, @Nullable String account, @Nullable Long userId,
                        @Nullable Long organizationId, @Nullable String role, @Nullable UUID invitation) {
        jdbc.update("""
                INSERT INTO identity_task (id, kind, dedupe_key, account, user_id, organization_id, org_role,
                                           invitation_id, status, attempts, next_attempt_at, created_at)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, now(), now())
                ON CONFLICT (dedupe_key) WHERE status = 'PENDING' DO UPDATE
                SET kind = excluded.kind, attempts = 0, next_attempt_at = now(), last_error = NULL
                """, kind.name(), dedupeKey, account, userId, organizationId, role, invitation);
    }
}
