package org.tornotron.echno_backend.invitation;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.identity.ExternalInvitationId;
import org.tornotron.echno_backend.common.identity.InvitationState;
import org.tornotron.echno_backend.common.identity.InvitationStatus;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.invitation.dto.InvitationDto;
import org.tornotron.echno_backend.invitation.dto.InvitationRoleDto;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvitationRecordsIT extends InvitationITSupport {

    /** One address per test: the fake provider is shared by every test in the context and counts its email. */
    private final String invited = "new.person." + System.nanoTime() + "@example.test";

    @Test
    void anInvitationIsRecordedWithItsRolesQueuedAndLogged() {
        signedInAs(OrgRole.SYSTEM_ADMIN);

        InvitationDto created = invitations.invite(invitation(" " + invited.toUpperCase() + " ",
                new InvitationRoleDto(role("site-engineer"), null)));

        assertThat(created.email()).isEqualTo(invited);
        assertThat(created.status()).isEqualTo(InvitationStage.PENDING);
        assertThat(created.sentAt()).isNull();
        assertThat(created.roles()).containsExactly(new InvitationRoleDto(role("site-engineer"), null));
        assertThat(created.employee().getDesignation()).isEqualTo("Site Engineer");
        assertThat(created.expiresAt()).isBetween(Instant.now().plus(6, ChronoUnit.DAYS), Instant.now().plus(8, ChronoUnit.DAYS));
        assertThat(tasksFor(created.id())).containsExactly("SEND_INVITATION");
        assertThat(logActionsAbout(created.id())).containsExactly(InvitationService.CREATED);
        assertThat(invitations.list(new PageQuery())).extracting(InvitationDto::id).containsExactly(created.id());
    }

    @Test
    void aRoleOnAProjectIsKept() {
        signedInAs(OrgRole.SYSTEM_ADMIN);
        Project project = fixtures.project(organization, "Tower " + System.nanoTime());
        em.flush();

        InvitationDto created = invitations.invite(invitation(invited,
                new InvitationRoleDto(role("procurement-manager"), project.getId())));

        assertThat(created.roles()).containsExactly(new InvitationRoleDto(role("procurement-manager"), project.getId()));
    }

    @Test
    void aSenderCannotNameARoleTheirRolesMayNotGive() {
        signedInAs(OrgRole.HR_ADMIN);

        assertThatThrownBy(() -> invitations.invite(invitation(invited,
                new InvitationRoleDto(role("system-admin"), null))))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invitation WHERE organization_id = ?",
                Integer.class, organization.getId())).isZero();
    }

    @Test
    void aRoleWithAnOldCounterpartCannotBeGivenOnAProject() {
        signedInAs(OrgRole.SYSTEM_ADMIN);
        Project project = fixtures.project(organization, "Tower " + System.nanoTime());
        em.flush();

        assertThatThrownBy(() -> invitations.invite(invitation(invited,
                new InvitationRoleDto(role("site-engineer"), project.getId()))))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void aRoleOrProjectOfAnotherOrganizationIsNotFound() {
        Organization other = anotherOrganization("Other");
        reconciler.reconcile(other.getId());
        UUID theirRole = role(other, "site-engineer");
        Project theirProject = fixtures.project(other, "Theirs " + System.nanoTime());
        em.flush();
        signedInAs(OrgRole.SYSTEM_ADMIN);

        assertThatThrownBy(() -> invitations.invite(invitation(invited, new InvitationRoleDto(theirRole, null))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> invitations.invite(invitation(invited,
                new InvitationRoleDto(role("procurement-manager"), theirProject.getId()))))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aReportingManagerIsRequiredOnceTheOrganizationHasEmployees() {
        signedInAs(OrgRole.SYSTEM_ADMIN);
        manager = null;

        assertThatThrownBy(() -> invitations.invite(invitation(invited)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("managerId");
    }

    @Test
    void nobodyInvitesThemselves() {
        Employee admin = signedInAs(OrgRole.SYSTEM_ADMIN);
        admin.getUser().setEmail("Admin.Self@example.test");
        em.flush();

        assertThatThrownBy(() -> invitations.invite(invitation("admin.self@example.test")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("themselves");
    }

    @Test
    void anOpenInvitationOrAnEmployeeAddressIsADuplicate() {
        signedInAs(OrgRole.SYSTEM_ADMIN);
        invitations.invite(invitation(invited));
        Employee existing = employee("existing");
        existing.getUser().setEmail("existing@example.test");
        em.flush();

        assertThatThrownBy(() -> invitations.invite(invitation(invited)))
                .isInstanceOf(DuplicateResourceException.class);
        assertThatThrownBy(() -> invitations.invite(invitation("existing@example.test")))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void anotherOrganizationsInvitationCannotBeReadResentOrRevoked() {
        signedInAs(OrgRole.SYSTEM_ADMIN);
        UUID id = invitations.invite(invitation(invited)).id();
        Organization other = anotherOrganization("Elsewhere");
        TenantContext.setCurrentOrgId(other.getId());

        assertThatThrownBy(() -> invitations.get(id)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> invitations.resend(id)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> invitations.revoke(id)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(invitations.list(new PageQuery())).isEmpty();
    }

    @Test
    void theLinkIsSentOnceWithTheRemainingExpiry() {
        signedInAs(OrgRole.SYSTEM_ADMIN);
        UUID id = invitations.invite(invitation(invited)).id();

        delivery.send(id);
        delivery.send(id);

        assertThat(provider.invitationEmailsTo(invited)).isEqualTo(1);
        assertThat(provider.invitationState(new ExternalInvitationId(providerIdOf(id))))
                .contains(InvitationStatus.of(InvitationState.PENDING));
        assertThat(invitations.get(id).sentAt()).isNotNull();
    }

    @Test
    void aResendRepeatsTheLinkAndKeepsTheExpiry() {
        signedInAs(OrgRole.SYSTEM_ADMIN);
        InvitationDto created = invitations.invite(invitation(invited));
        delivery.send(created.id());

        InvitationDto resent = invitations.resend(created.id());
        delivery.resend(created.id());

        assertThat(resent.expiresAt()).isEqualTo(created.expiresAt());
        assertThat(tasksFor(created.id())).containsExactly("RESEND_INVITATION");
        assertThat(provider.invitationEmailsTo(invited)).isEqualTo(2);
        assertThat(logActionsAbout(created.id())).containsExactly(InvitationService.CREATED, InvitationService.RESENT);
    }

    @Test
    void resendingAnExpiredInvitationRenewsItWithANewLink() {
        signedInAs(OrgRole.SYSTEM_ADMIN);
        UUID id = invitations.invite(invitation(invited)).id();
        delivery.send(id);
        String firstLink = providerIdOf(id);
        jdbc.update("UPDATE invitation SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), id);
        assertThat(invitations.get(id).status()).isEqualTo(InvitationStage.EXPIRED);

        InvitationDto renewed = invitations.resend(id);
        delivery.renew(id);

        assertThat(renewed.status()).isEqualTo(InvitationStage.PENDING);
        assertThat(renewed.expiresAt()).isAfter(Instant.now().plus(6, ChronoUnit.DAYS));
        assertThat(providerIdOf(id)).isNotEqualTo(firstLink);
        assertThat(provider.invitationState(new ExternalInvitationId(firstLink)))
                .contains(InvitationStatus.of(InvitationState.REVOKED));
        assertThat(logActionsAbout(id)).containsExactly(InvitationService.CREATED, InvitationService.RENEWED);
    }

    @Test
    void aRevokedInvitationStopsHereAndAtTheProvider() {
        signedInAs(OrgRole.SYSTEM_ADMIN);
        UUID id = invitations.invite(invitation(invited)).id();
        delivery.send(id);

        InvitationDto revoked = invitations.revoke(id);
        delivery.revoke(id);

        assertThat(revoked.status()).isEqualTo(InvitationStage.REVOKED);
        assertThat(provider.invitationState(new ExternalInvitationId(providerIdOf(id))))
                .contains(InvitationStatus.of(InvitationState.REVOKED));
        assertThatThrownBy(() -> invitations.resend(id)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> invitations.revoke(id)).isInstanceOf(InvalidRequestException.class);
        assertThat(logActionsAbout(id)).containsExactly(InvitationService.CREATED, InvitationService.REVOKED);
    }

    @Test
    void aLinkRevokedBeforeItWentIsNeverSent() {
        signedInAs(OrgRole.SYSTEM_ADMIN);
        UUID id = invitations.invite(invitation(invited)).id();

        invitations.revoke(id);
        delivery.send(id);
        delivery.revoke(id);

        assertThat(tasksFor(id)).containsExactly("REVOKE_INVITATION");
        assertThat(provider.invitationEmailsTo(invited)).isZero();
    }
}
