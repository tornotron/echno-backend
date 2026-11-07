package org.tornotron.echno_backend.invitation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.identity.EchnoOrgId;
import org.tornotron.echno_backend.common.identity.ExternalInvitationId;
import org.tornotron.echno_backend.common.identity.ExternalUserId;
import org.tornotron.echno_backend.common.identity.IdentityProvider;
import org.tornotron.echno_backend.common.identity.InvitationNotPendingException;
import org.tornotron.echno_backend.common.identity.link.UserIdentityLookup;
import org.tornotron.echno_backend.common.identity.task.InvitationDelivery;
import org.tornotron.echno_backend.user.UserRepository;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Makes the provider calls for an invitation, from the identity task queue, with no tenant and no
 * transaction held across the call. Each reads the record as it is now, so a revoke that arrived after
 * a send was queued wins, and each is safe to repeat.
 */
@Slf4j
@Service
public class InvitationDeliveryService implements InvitationDelivery {

    private record Open(Long organization, String email, Instant expiresAt, @Nullable String providerId,
                        @Nullable Long invitedBy) {
    }

    private final JdbcTemplate jdbc;
    private final IdentityProvider provider;
    private final UserRepository users;
    private final UserIdentityLookup accounts;
    private final Clock clock = Clock.systemUTC();

    public InvitationDeliveryService(JdbcTemplate jdbc, IdentityProvider provider, UserRepository users,
                                     UserIdentityLookup accounts) {
        this.jdbc = jdbc;
        this.provider = provider;
        this.users = users;
        this.accounts = accounts;
    }

    @Override
    public void send(UUID invitation) {
        open(invitation).filter(open -> open.providerId() == null).ifPresent(open -> {
            Duration left = Duration.between(clock.instant(), open.expiresAt());
            if (left.isNegative() || left.isZero()) {
                return;
            }
            ExternalInvitationId sent = provider.invite(open.email(), new EchnoOrgId(open.organization()),
                    inviter(open.invitedBy()), left);
            jdbc.update("""
                    UPDATE invitation SET provider_invitation_id = ?, sent_at = now(), updated_at = now()
                    WHERE id = ? AND provider_invitation_id IS NULL
                    """, sent.value(), invitation);
        });
    }

    @Override
    public void resend(UUID invitation) {
        open(invitation).ifPresent(open -> {
            if (open.providerId() == null) {
                send(invitation);
                return;
            }
            try {
                provider.resendInvitation(new ExternalInvitationId(open.providerId()));
            } catch (InvitationNotPendingException stale) {
                log.info("Invitation {} was no longer pending at the provider, so it was not resent", invitation);
            }
        });
    }

    @Override
    public void renew(UUID invitation) {
        open(invitation).ifPresent(open -> {
            if (open.providerId() != null) {
                withdraw(open.providerId());
                jdbc.update("""
                        UPDATE invitation SET provider_invitation_id = NULL, sent_at = NULL, updated_at = now()
                        WHERE id = ? AND provider_invitation_id = ?
                        """, invitation, open.providerId());
            }
            send(invitation);
        });
    }

    @Override
    public void revoke(UUID invitation) {
        jdbc.queryForList("SELECT provider_invitation_id FROM invitation WHERE id = ? AND provider_invitation_id IS NOT NULL",
                String.class, invitation).forEach(this::withdraw);
    }

    private void withdraw(String providerId) {
        try {
            provider.revokeInvitation(new ExternalInvitationId(providerId));
        } catch (InvitationNotPendingException stale) {
            log.info("A provider invitation was no longer pending when it was withdrawn");
        }
    }

    /** The invitation, while it is still pending. */
    private Optional<Open> open(UUID invitation) {
        return jdbc.query("""
                SELECT organization_id, email, expires_at, provider_invitation_id, invited_by_user_id
                FROM invitation WHERE id = ? AND status = 'PENDING'
                """, (rs, index) -> new Open(rs.getLong(1), rs.getString(2), toInstant(rs.getTimestamp(3)),
                rs.getString(4), rs.getObject(5, Long.class)), invitation).stream().findFirst();
    }

    @Nullable
    private ExternalUserId inviter(@Nullable Long userId) {
        return userId == null ? null : users.findById(userId).flatMap(accounts::accountOf).orElse(null);
    }

    private static Instant toInstant(Timestamp value) {
        return value.toInstant();
    }
}
