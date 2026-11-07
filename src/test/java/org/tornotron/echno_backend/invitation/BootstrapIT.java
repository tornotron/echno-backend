package org.tornotron.echno_backend.invitation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.identity.ExternalInvitationId;
import org.tornotron.echno_backend.common.identity.link.IdentityLinks;
import org.tornotron.echno_backend.common.identity.task.IdentityTaskQueue;
import org.tornotron.echno_backend.common.identity.task.IdentityTaskWorker;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.organization.BootstrapRunner;
import org.tornotron.echno_backend.organization.OrganizationOnboardingSeeder;
import org.tornotron.echno_backend.organization.OrganizationProvisioning;
import org.tornotron.echno_backend.organization.OrganizationRepository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/** Section 7 of the build plan: a fresh database to an invitation for the first System Admin, and no more. */
@Import({OrganizationProvisioning.class, IdentityTaskWorker.class, IdentityLinks.class})
class BootstrapIT extends InvitationITSupport {

    @Autowired
    private OrganizationProvisioning provisioning;
    @Autowired
    private IdentityTaskQueue tasks;
    @Autowired
    private IdentityTaskWorker worker;
    @Autowired
    private OrganizationRepository organizations;
    @MockitoBean
    private OrganizationOnboardingSeeder seeder;
    @MockitoBean
    private TransactionRetryTemplate retry;

    private final String organizationEmail = "office." + System.nanoTime() + "@example.test";
    private final String adminEmail = "first.admin." + System.nanoTime() + "@example.test";
    private BootstrapRunner runner;

    @BeforeEach
    void aRunnerForAFreshStack() {
        doAnswer(call -> {
            call.getArgument(1, Runnable.class).run();
            return null;
        }).when(retry).executeWithoutResult(anyString(), any(Runnable.class));
        runner = new BootstrapRunner(jdbc, retry, provisioning, invitations, tasks,
                "First Organization", organizationEmail, adminEmail);
        TenantContext.clear();
    }

    private Long bootstrapped() {
        return jdbc.queryForObject("SELECT id FROM organization WHERE organization_email = ?", Long.class, organizationEmail);
    }

    private List<String> adminInvitations() {
        return jdbc.queryForList("SELECT status FROM invitation WHERE email = ? ORDER BY created_at", String.class, adminEmail);
    }

    @Test
    void aFreshDatabaseEndsWithTheOrganizationAndAnInvitationSent() {
        runner.run();
        worker.runDue();

        Long organization = bootstrapped();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM access_role WHERE organization_id = ? AND slug = 'system-admin'",
                Integer.class, organization)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organization_identity WHERE organization_id = ?",
                Integer.class, organization)).isEqualTo(1);
        assertThat(adminInvitations()).containsExactly("PENDING");
        assertThat(provider.invitationEmailsTo(adminEmail)).isEqualTo(1);
        UUID invitation = jdbc.queryForObject("SELECT id FROM invitation WHERE email = ?", UUID.class, adminEmail);
        assertThat(jdbc.queryForObject("SELECT expires_at FROM invitation WHERE id = ?", Timestamp.class, invitation)
                .toInstant()).isAfter(Instant.now().plus(29, ChronoUnit.DAYS));
        assertThat(jdbc.queryForObject("SELECT r.slug FROM invitation_role i JOIN access_role r ON r.id = i.role_id "
                + "WHERE i.invitation_id = ?", String.class, invitation)).isEqualTo("system-admin");
        assertThat(organizations.findById(organization)).get()
                .satisfies(created -> assertThat(created.getCreatorId()).isNull());
    }

    @Test
    void aRerunSendsNothing() {
        runner.run();
        worker.runDue();

        runner.run();
        worker.runDue();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM organization WHERE organization_email = ?",
                Integer.class, organizationEmail)).isEqualTo(1);
        assertThat(adminInvitations()).containsExactly("PENDING");
        assertThat(provider.invitationEmailsTo(adminEmail)).isEqualTo(1);
    }

    @Test
    void aRerunAfterTheInvitationRanOutWithNoAdministratorSendsANewOne() {
        runner.run();
        worker.runDue();
        jdbc.update("UPDATE invitation SET expires_at = ? WHERE email = ?",
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), adminEmail);
        provider.expireInvitation(new ExternalInvitationId(jdbc.queryForObject(
                "SELECT provider_invitation_id FROM invitation WHERE email = ?", String.class, adminEmail)));

        runner.run();
        worker.runDue();

        // One test transaction, so both rows carry the same creation time.
        assertThat(adminInvitations()).containsExactlyInAnyOrder("EXPIRED", "PENDING");
        assertThat(provider.invitationEmailsTo(adminEmail)).isEqualTo(2);
    }

    @Test
    void anOrganizationWithAnActiveAdministratorIsLeftAlone() {
        runner.run();
        worker.runDue();
        organization = organizations.findById(bootstrapped()).orElseThrow();
        TenantContext.setCurrentOrgId(organization.getId());
        Employee admin = employee("admin");
        employeeService.assignOrgRoleAsSystem(admin.getId(), OrgRole.SYSTEM_ADMIN);
        em.flush();
        jdbc.update("UPDATE invitation SET status = 'REVOKED' WHERE email = ?", adminEmail);
        TenantContext.clear();

        runner.run();
        worker.runDue();

        assertThat(adminInvitations()).containsExactly("REVOKED");
    }

    @Test
    void withoutItsSettingsItDoesNothing() {
        new BootstrapRunner(jdbc, retry, provisioning, invitations, tasks, "", organizationEmail, adminEmail).run();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM organization WHERE organization_email = ?",
                Integer.class, organizationEmail)).isZero();
    }
}
