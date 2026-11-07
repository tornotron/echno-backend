package org.tornotron.echno_backend.organization;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.identity.EchnoOrgId;
import org.tornotron.echno_backend.common.identity.task.IdentityTaskQueue;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.startup.StartupTask;
import org.tornotron.echno_backend.employee.dto.EmployeeJoinOrgDto;
import org.tornotron.echno_backend.invitation.InvitationService;
import org.tornotron.echno_backend.invitation.dto.InvitationRoleDto;
import org.tornotron.echno_backend.organization.dto.OrganizationCreationDto;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Starts the first organization of a stack where nobody can sign up: the organization and its roles,
 * and an invitation holding System Admin for the first administrator, which the provider sends. It
 * runs on every boot while {@code ECHNO_BOOTSTRAP_ORG_NAME}, {@code ECHNO_BOOTSTRAP_ORG_EMAIL} and
 * {@code ECHNO_BOOTSTRAP_ADMIN_EMAIL} are all set, under the startup task lock.
 *
 * <ul>
 *   <li>Once the organization has an active System Admin it does nothing, so the settings left in
 *       place are harmless.</li>
 *   <li>An organization with that email that already exists is used, not created again.</li>
 *   <li>While the administrator's invitation is open it sends nothing. Once it has run out, with
 *       nobody having accepted, the next boot sends a new one.</li>
 * </ul>
 *
 * <p>The provider organization is created through the identity task queue before the invitation is
 * sent, so nothing here waits on the provider. Billing is not consulted: there is no user to bill.
 * The organization's address and phone are left blank for the administrator to fill in.
 */
@Slf4j
@Component
@Order(BootstrapRunner.ORDER)
public class BootstrapRunner implements StartupTask {

    public static final String NAME = "first-organization-bootstrap";
    static final int ORDER = 500;
    static final Duration ADMIN_INVITATION = Duration.ofDays(30);
    private static final String ADMINISTRATOR = "system-admin";

    private final JdbcTemplate jdbc;
    private final TransactionRetryTemplate transactions;
    private final OrganizationProvisioning provisioning;
    private final InvitationService invitations;
    private final IdentityTaskQueue tasks;
    private final String organizationName;
    private final String organizationEmail;
    private final String adminEmail;

    public BootstrapRunner(JdbcTemplate jdbc, TransactionRetryTemplate transactions,
                           OrganizationProvisioning provisioning, InvitationService invitations, IdentityTaskQueue tasks,
                           @Value("${echno.bootstrap.organization-name:}") String organizationName,
                           @Value("${echno.bootstrap.organization-email:}") String organizationEmail,
                           @Value("${echno.bootstrap.admin-email:}") String adminEmail) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.provisioning = provisioning;
        this.invitations = invitations;
        this.tasks = tasks;
        this.organizationName = organizationName.trim();
        this.organizationEmail = organizationEmail.trim();
        this.adminEmail = adminEmail.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run() {
        if (organizationName.isEmpty() || organizationEmail.isEmpty() || adminEmail.isEmpty()) {
            return;
        }
        transactions.executeWithoutResult(NAME, this::bootstrap);
    }

    private void bootstrap() {
        Optional<Long> existing = jdbc.queryForList(
                "SELECT id FROM organization WHERE lower(organization_email) = lower(?) ORDER BY id",
                Long.class, organizationEmail).stream().findFirst();
        if (existing.isPresent() && hasActiveAdministrator(existing.get())) {
            return;
        }
        Long organization = existing.orElseGet(this::create);
        if (!hasProviderOrganization(organization)) {
            tasks.createOrganization(new EchnoOrgId(organization));
        }
        if (hasOpenInvitation(organization)) {
            return;
        }
        UUID administrator = jdbc.queryForObject("SELECT id FROM access_role WHERE organization_id = ? AND slug = ?",
                UUID.class, organization, ADMINISTRATOR);
        EmployeeJoinOrgDto employee = new EmployeeJoinOrgDto();
        employee.setDesignation("System Admin");
        invitations.create(organization, adminEmail, List.of(new InvitationRoleDto(administrator, null)), employee,
                null, ADMIN_INVITATION);
        log.info("Bootstrap: invited the first administrator of organization {}", organization);
    }

    private Long create() {
        OrganizationCreationDto details = new OrganizationCreationDto();
        details.setOrganizationName(organizationName);
        details.setOrganizationEmail(organizationEmail);
        details.setOrganizationAddress("");
        details.setOrganizationPhone("");
        Long id = provisioning.provision(details, null).getId();
        log.info("Bootstrap: created organization {}", id);
        return id;
    }

    private boolean hasActiveAdministrator(Long organization) {
        return count("""
                SELECT count(*) FROM access_employee_role a
                JOIN access_role r ON r.id = a.role_id
                JOIN employee e ON e.id = a.employee_id
                WHERE r.organization_id = ? AND r.slug = ? AND a.project_id IS NULL
                  AND e.status IN ('active', 'onLeave', 'probation')
                """, organization, ADMINISTRATOR) > 0;
    }

    private boolean hasProviderOrganization(Long organization) {
        return count("SELECT count(*) FROM organization_identity WHERE organization_id = ?", organization) > 0;
    }

    private boolean hasOpenInvitation(Long organization) {
        return count("""
                SELECT count(*) FROM invitation
                WHERE organization_id = ? AND email = ? AND status = 'PENDING' AND expires_at > now()
                """, organization, adminEmail) > 0;
    }

    private int count(String sql, Object... arguments) {
        Integer count = jdbc.queryForObject(sql, Integer.class, arguments);
        return count == null ? 0 : count;
    }
}
