package org.tornotron.echno_backend.common.identity.keycloak;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.identity.ConditionalOnKeycloak;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.startup.StartupTask;
import org.tornotron.echno_backend.common.startup.StartupTaskState;
import org.tornotron.echno_backend.employee.startup.NullEmployeeStatusCleanup;

/**
 * Copies each employee's Keycloak role subgroups into {@code employee_org_roles}, so that table is
 * complete before it becomes the truth. Until now the two were written side by side and could drift:
 * an administrator could add someone to a subgroup in Keycloak directly, and the token's groups would
 * have honoured it.
 *
 * <p>Only ever adds a row. Every row it adds is logged, by employee and role, because each one is a
 * place the two disagreed. A subgroup member with no employee in the organization is logged and left
 * out; that includes machine logins, which keep their groups from the token.
 *
 * <p>The Keycloak calls for one organization happen before its transaction, and a failure at Keycloak
 * skips that organization and fails the task at the end. Until the task has finished once,
 * {@link #NAME} stays unrecorded in {@code startup_task_state}, which is what the reader of these
 * tables checks before it stops trusting the token's groups.
 */
@Component
@ConditionalOnKeycloak
@Order(StartupTaskOrder.ROLE_IMPORT)
@Slf4j
public class KeycloakRoleImport implements StartupTask {

    public static final String NAME = "keycloak-role-import";

    private final JdbcTemplate jdbc;
    private final TransactionRetryTemplate transactions;
    private final KeycloakGroupService groups;
    private final KeycloakTokenMapper mapper;
    private final StartupTaskState state;

    public KeycloakRoleImport(JdbcTemplate jdbc, TransactionRetryTemplate transactions,
                              KeycloakGroupService groups, KeycloakTokenMapper mapper,
                              StartupTaskState state) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.groups = groups;
        this.mapper = mapper;
        this.state = state;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run() {
        // Finishing this is what stops a person's token groups counting, and a person with a null status
        // holds nothing once they stop, so the clean-up has to be done first.
        if (!state.hasCompleted(NullEmployeeStatusCleanup.NAME)) {
            throw new IllegalStateException("Not importing until the null employee status clean-up has finished");
        }
        List<Long> organizations = jdbc.queryForList("SELECT id FROM organization ORDER BY id", Long.class);
        List<Long> failed = new ArrayList<>();
        int added = 0;
        for (Long organization : organizations) {
            try {
                added += importOrganization(organization);
            } catch (RuntimeException e) {
                failed.add(organization);
                log.error("Could not import the Keycloak roles of organization {}", organization, e);
            }
        }
        log.info("Keycloak role import added {} rows across {} organizations", added, organizations.size());
        if (!failed.isEmpty()) {
            throw new IllegalStateException("Keycloak roles not imported for organizations " + failed);
        }
    }

    private int importOrganization(Long organization) {
        recordMembersWithNoEmployee(organization, !state.hasCompleted(NAME));
        Map<OrgRole, List<String>> members = groups.roleSubgroupMembers(Long.toString(organization));
        if (members.isEmpty()) {
            return 0;
        }
        return transactions.execute("KeycloakRoleImport", () -> {
            int added = 0;
            for (Map.Entry<OrgRole, List<String>> role : members.entrySet()) {
                for (String subject : role.getValue()) {
                    added += importMember(organization, role.getKey(), subject);
                }
            }
            return added;
        });
    }

    /**
     * Membership moves to the employee table next, so a person in the organization's group with no
     * employee there would stop being a member. Nobody may lose access over this, so the first
     * finished import records them, and their token's groups keep deciding for that organization.
     *
     * <p>Only the first: after that, a group member with no employee is someone whose removal has not
     * reached Keycloak yet, and recording them would hand their access back. A later run only drops the
     * rows of accounts that have since got an employee.
     */
    private void recordMembersWithNoEmployee(Long organization, boolean firstImport) {
        jdbc.update("""
                DELETE FROM keycloak_group_only_member
                WHERE organization_id = ?
                  AND subject IN (SELECT i.subject FROM user_identity i
                                  JOIN employee e ON e.user_id = i.user_id AND e.organization_id = ?)
                """, organization, organization);
        if (!firstImport) {
            return;
        }
        for (String subject : groups.organizationGroupMembers(Long.toString(organization))) {
            if (employeeOf(organization, subject).isEmpty()) {
                jdbc.update("""
                        INSERT INTO keycloak_group_only_member (subject, organization_id) VALUES (?, ?)
                        ON CONFLICT DO NOTHING
                        """, subject, organization);
                log.info("Keycloak subject {} is in organization {}'s group with no employee there; "
                        + "its token groups keep deciding for that organization", subject, organization);
            }
        }
    }

    private int importMember(Long organization, OrgRole role, String subject) {
        Optional<Long> employee = employeeOf(organization, subject);
        if (employee.isEmpty()) {
            log.info("Keycloak subject {} holds {} in organization {} but has no employee there; left out",
                    subject, role, organization);
            return 0;
        }
        int added = jdbc.update(
                "INSERT INTO employee_org_roles (employee_id, role) VALUES (?, ?) ON CONFLICT DO NOTHING",
                employee.get(), role.name());
        if (added > 0) {
            log.info("Imported {} for employee {} in organization {} from Keycloak", role, employee.get(), organization);
        }
        return added;
    }

    private Optional<Long> employeeOf(Long organization, String subject) {
        return jdbc.queryForList("""
                SELECT e.id FROM employee e
                WHERE e.organization_id = ?
                  AND e.user_id IN (SELECT user_id FROM user_identity WHERE issuer = ? AND subject = ?
                                    UNION SELECT id FROM users_table WHERE keycloak_id = ?)
                """, Long.class, organization, mapper.issuer(), subject, subject).stream().findFirst();
    }
}
