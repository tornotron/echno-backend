package org.tornotron.echno_backend.common.identity.task;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.identity.EchnoOrgId;
import org.tornotron.echno_backend.common.identity.ExternalUserId;

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
        REMOVE_MEMBER, END_SESSIONS, ASSIGN_ROLE, REMOVE_ROLE
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

    private void enqueue(Kind kind, String dedupeKey, ExternalUserId account, @Nullable Long userId,
                         @Nullable Long organizationId, @Nullable String role) {
        jdbc.update("""
                INSERT INTO identity_task (id, kind, dedupe_key, account, user_id, organization_id, org_role,
                                           status, attempts, next_attempt_at, created_at)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, 'PENDING', 0, now(), now())
                ON CONFLICT (dedupe_key) WHERE status = 'PENDING' DO UPDATE
                SET kind = excluded.kind, attempts = 0, next_attempt_at = now(), last_error = NULL
                """, kind.name(), dedupeKey, account.value(), userId, organizationId, role);
    }
}
