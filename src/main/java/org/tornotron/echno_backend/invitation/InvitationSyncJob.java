package org.tornotron.echno_backend.invitation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.identity.EchnoOrgId;
import org.tornotron.echno_backend.common.identity.ExternalInvitationId;
import org.tornotron.echno_backend.common.identity.IdentityProvider;
import org.tornotron.echno_backend.common.identity.InvitationStatus;
import org.tornotron.echno_backend.common.identity.UserProfile;
import org.tornotron.echno_backend.common.identity.task.IdentityTaskQueue;
import org.tornotron.echno_backend.modules.access.api.AccessChanges;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Brings pending invitations in line with the provider, so the invitation screen tells the truth
 * without anyone signing in. An invitation that expired before it was ever sent, or that is expired or
 * revoked at the provider, is marked so. A sent one is judged by the provider alone, since a link
 * accepted just before it ran out still has to be linked at the person's first sign-in. One accepted by an address other than the invited one is marked
 * {@link InvitationStage#ACCEPTED_BY_ANOTHER_ADDRESS}, and the provider membership it made is removed.
 * One accepted by the invited address is left for {@link IdentityLinkService} to link at sign-in.
 *
 * <p>Holds no transaction across a provider call. {@code echno.invitations.sync.enabled=false} stops the
 * schedule, which the test suite does.
 */
@Slf4j
@Component
public class InvitationSyncJob {

    static final String ACCEPTED_BY_ANOTHER = "invitation.accepted-by-another-address";
    static final String EXPIRED = "invitation.expired";
    private static final int BATCH = 100;

    private record Pending(UUID id, Long organization, String email, String providerId) {
    }

    private final JdbcTemplate jdbc;
    private final IdentityProvider provider;
    private final IdentityTaskQueue tasks;
    private final AccessChanges changes;
    private final boolean enabled;

    public InvitationSyncJob(JdbcTemplate jdbc, IdentityProvider provider, IdentityTaskQueue tasks,
                             AccessChanges changes, @Value("${echno.invitations.sync.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.provider = provider;
        this.tasks = tasks;
        this.changes = changes;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${echno.invitations.sync.poll-ms:300000}")
    void onSchedule() {
        if (!enabled) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.error("Invitation sync pass failed; the next one tries again", e);
        }
    }

    /** One pass: expire what lapsed unsent, then ask the provider about the rest, least recently seen first. */
    public void runOnce() {
        List<UUID> lapsed = jdbc.queryForList("""
                UPDATE invitation SET status = 'EXPIRED', updated_at = now()
                WHERE status = 'PENDING' AND expires_at <= now() AND provider_invitation_id IS NULL
                RETURNING id
                """, UUID.class);
        lapsed.forEach(id -> recordAbout(id, EXPIRED, InvitationStage.EXPIRED));
        List<Pending> pending = jdbc.query("""
                SELECT id, organization_id, email, provider_invitation_id FROM invitation
                WHERE status = 'PENDING' AND provider_invitation_id IS NOT NULL
                ORDER BY updated_at LIMIT ?
                """, (rs, index) -> new Pending(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3),
                rs.getString(4)), BATCH);
        for (Pending invitation : pending) {
            try {
                check(invitation);
            } catch (RuntimeException failure) {
                log.warn("Invitation {} could not be checked at the provider: {}", invitation.id(), failure.getMessage());
            }
        }
    }

    private void check(Pending invitation) {
        Optional<InvitationStatus> status = provider.invitationState(new ExternalInvitationId(invitation.providerId()));
        jdbc.update("UPDATE invitation SET updated_at = now() WHERE id = ?", invitation.id());
        if (status.isEmpty()) {
            return;
        }
        switch (status.get().state()) {
            case PENDING -> { }
            case EXPIRED -> mark(invitation, InvitationStage.EXPIRED, EXPIRED);
            case REVOKED -> mark(invitation, InvitationStage.REVOKED, "invitation.revoked-at-provider");
            case ACCEPTED -> {
                if (status.get().acceptedUser() == null) {
                    return;
                }
                Optional<String> acceptedAddress = provider.profile(status.get().acceptedUser())
                        .map(UserProfile::email)
                        .map(InvitationService::normalized);
                if (acceptedAddress.isPresent() && !acceptedAddress.get().equals(invitation.email())) {
                    mark(invitation, InvitationStage.ACCEPTED_BY_ANOTHER_ADDRESS, ACCEPTED_BY_ANOTHER);
                    tasks.removeMember(status.get().acceptedUser(), new EchnoOrgId(invitation.organization()));
                }
            }
        }
    }

    private void mark(Pending invitation, InvitationStage stage, String action) {
        int changed = jdbc.update("UPDATE invitation SET status = ?, updated_at = now() WHERE id = ? AND status = 'PENDING'",
                stage.name(), invitation.id());
        if (changed > 0) {
            changes.record(invitation.organization(), null, action, InvitationService.TARGET, invitation.id().toString(),
                    Map.of("status", InvitationStage.PENDING.name()), Map.of("status", stage.name()));
        }
    }

    private void recordAbout(UUID id, String action, InvitationStage stage) {
        Long organization = jdbc.queryForObject("SELECT organization_id FROM invitation WHERE id = ?", Long.class, id);
        changes.record(organization, null, action, InvitationService.TARGET, id.toString(),
                Map.of("status", InvitationStage.PENDING.name()), Map.of("status", stage.name()));
    }
}
