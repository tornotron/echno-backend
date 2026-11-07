package org.tornotron.echno_backend.invitation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.identity.ExternalInvitationId;
import org.tornotron.echno_backend.common.identity.ExternalUserId;
import org.tornotron.echno_backend.common.identity.IdentityTokenMapper;
import org.tornotron.echno_backend.common.identity.Principal;
import org.tornotron.echno_backend.common.identity.SignInMethod;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.invitation.dto.InvitationRoleDto;
import org.tornotron.echno_backend.organization.Organization;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Design note 3.4, step 4: a link only for an accepted invitation, by this account, at the invited address. */
class InvitationAcceptanceIT extends InvitationITSupport {

    /** One address per test: the fake provider is shared by every test in the context. */
    private final String invited = "invited.person." + System.nanoTime() + "@example.test";

    @Autowired
    private IdentityLinkService links;
    @Autowired
    private InvitationSyncJob sync;
    @Autowired
    private IdentityTokenMapper mapper;

    /** An invitation from a System Admin of the current organization, already sent. */
    private UUID sentInvitation(String email, InvitationRoleDto... roles) {
        signedInAs(OrgRole.SYSTEM_ADMIN);
        UUID id = invitations.invite(invitation(email, roles)).id();
        delivery.send(id);
        return id;
    }

    private ExternalInvitationId atProvider(UUID invitation) {
        return new ExternalInvitationId(providerIdOf(invitation));
    }

    private Principal signingIn(ExternalUserId account, String tokenEmail) {
        return new Principal(mapper.issuer(), account.value(), tokenEmail, SignInMethod.UNKNOWN);
    }

    private String stageOf(UUID invitation) {
        em.flush();
        return jdbc.queryForObject("SELECT status FROM invitation WHERE id = ?", String.class, invitation);
    }

    private List<Long> employeesOf(Long userId) {
        em.flush();
        return jdbc.queryForList("SELECT organization_id FROM employee WHERE user_id = ? ORDER BY id", Long.class, userId);
    }

    @Test
    void theInvitedAddressBecomesAnEmployeeWithTheRoles() {
        reconciler.reconcile(organization.getId());
        UUID id = sentInvitation(invited, new InvitationRoleDto(role("site-engineer"), null));
        ExternalUserId account = provider.acceptInvitation(atProvider(id), invited);

        Optional<Long> user = links.reconcile(signingIn(account, invited));

        assertThat(user).isPresent();
        assertThat(employeesOf(user.get())).containsExactly(organization.getId());
        assertThat(jdbc.queryForList("""
                SELECT r.slug FROM access_employee_role a JOIN access_role r ON r.id = a.role_id
                JOIN employee e ON e.id = a.employee_id WHERE e.user_id = ?
                """, String.class, user.get())).contains("site-engineer");
        assertThat(jdbc.queryForObject("SELECT status FROM employee WHERE user_id = ?", String.class, user.get()))
                .isEqualTo("active");
        assertThat(stageOf(id)).isEqualTo("ACCEPTED");
        assertThat(logActionsAbout(id)).contains(IdentityLinkService.ACCEPTED);
        assertThat(jdbc.queryForObject("SELECT email FROM users_table WHERE id = ?", String.class, user.get()))
                .isEqualTo(invited);
    }

    @Test
    void signingInAgainLinksNothingMore() {
        UUID id = sentInvitation(invited);
        ExternalUserId account = provider.acceptInvitation(atProvider(id), invited);
        Long user = links.reconcile(signingIn(account, invited)).orElseThrow();

        assertThat(links.reconcile(signingIn(account, invited))).contains(user);
        assertThat(employeesOf(user)).hasSize(1);
    }

    @Test
    void anotherAddressThatAcceptedLinksNothingAndIsShownAsSuch() {
        UUID id = sentInvitation(invited);
        ExternalUserId colleague = provider.acceptInvitation(atProvider(id), "colleague." + System.nanoTime() + "@example.test");

        assertThat(links.reconcile(signingIn(colleague, invited))).isEmpty();
        assertThat(stageOf(id)).isEqualTo("PENDING");

        sync.runOnce();

        assertThat(stageOf(id)).isEqualTo(InvitationStage.ACCEPTED_BY_ANOTHER_ADDRESS.name());
        assertThat(jdbc.queryForList("SELECT kind FROM identity_task WHERE account = ? AND status = 'PENDING'",
                String.class, colleague.value())).containsExactly("REMOVE_MEMBER");
        assertThat(logActionsAbout(id)).contains(InvitationSyncJob.ACCEPTED_BY_ANOTHER);
    }

    @Test
    void anUnverifiedAddressLinksNothing() {
        UUID id = sentInvitation(invited);
        provider.addUser(invited, false, "Not Yet Verified");
        ExternalUserId account = provider.acceptInvitation(atProvider(id), invited);

        assertThat(links.reconcile(signingIn(account, invited))).isEmpty();
        assertThat(stageOf(id)).isEqualTo("PENDING");
    }

    @Test
    void anExistingUserJoinsASecondOrganization() {
        UUID first = sentInvitation(invited);
        ExternalUserId account = provider.acceptInvitation(atProvider(first), invited);
        Long user = links.reconcile(signingIn(account, invited)).orElseThrow();
        Organization second = anotherOrganization("Second");
        organization = second;
        TenantContext.setCurrentOrgId(second.getId());
        UUID secondInvitation = sentInvitation(invited);

        assertThat(provider.acceptInvitation(atProvider(secondInvitation), invited)).isEqualTo(account);
        assertThat(links.reconcile(signingIn(account, invited))).contains(user);

        assertThat(employeesOf(user)).hasSize(2).contains(second.getId());
    }

    @Test
    void anExpiredInvitationLinksNothingAndIsShownAsExpired() {
        UUID id = sentInvitation(invited);
        provider.expireInvitation(atProvider(id));
        ExternalUserId account = provider.addUser(invited, true, "Too Late");

        assertThat(links.reconcile(signingIn(account, invited))).isEmpty();
        sync.runOnce();

        assertThat(stageOf(id)).isEqualTo("EXPIRED");
    }

    @Test
    void anInvitationRevokedHereLinksNothingEvenIfAcceptedThere() {
        UUID id = sentInvitation(invited);
        ExternalUserId account = provider.acceptInvitation(atProvider(id), invited);
        invitations.revoke(id);

        assertThat(links.reconcile(signingIn(account, invited))).isEmpty();
        assertThat(stageOf(id)).isEqualTo("REVOKED");
    }

    @Test
    void theTokenEmailIsNeverTrusted() {
        UUID id = sentInvitation(invited);
        ExternalUserId stranger = provider.addUser("stranger." + System.nanoTime() + "@example.test", true, "Stranger");
        provider.acceptInvitation(atProvider(id), invited);

        assertThat(links.reconcile(signingIn(stranger, invited))).isEmpty();
        assertThat(stageOf(id)).isEqualTo("PENDING");
    }
}
