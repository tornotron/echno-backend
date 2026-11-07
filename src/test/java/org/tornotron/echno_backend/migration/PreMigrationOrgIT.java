package org.tornotron.echno_backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.identity.keycloak.KeycloakGroupService;
import org.tornotron.echno_backend.common.identity.keycloak.KeycloakIdentityBackfill;
import org.tornotron.echno_backend.common.identity.keycloak.KeycloakRoleImport;
import org.tornotron.echno_backend.common.identity.keycloak.KeycloakTokenMapper;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;
import org.tornotron.echno_backend.common.startup.StartupTaskRunner;
import org.tornotron.echno_backend.common.startup.StartupTaskState;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.employee.startup.NullEmployeeStatusCleanup;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/**
 * An organization and its people built the old way, with only {@code users_table.keycloak_id} and the
 * old tables to go on, then the startup tasks, as a staging's data meets them on its first boot. Each
 * check runs the tasks twice, because every boot runs them again.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({PreMigrationOrgIT.Wiring.class, StartupTaskRunner.class, StartupTaskState.class,
        KeycloakIdentityBackfill.class, NullEmployeeStatusCleanup.class, KeycloakRoleImport.class,
        KeycloakTokenMapper.class, TransactionRetryTemplate.class, TransactionalWorkRunner.class})
class PreMigrationOrgIT extends AbstractIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Wiring {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private EntityManager em;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private KeycloakGroupService keycloakGroups;

    @Autowired
    private StartupTaskRunner runner;

    @Autowired
    private StartupTaskState state;

    private TransactionTemplate transaction;
    private Long organization;
    private Long employee;
    private Long user;
    private String subject;

    @BeforeEach
    void anOrganizationBuiltTheOldWay() {
        transaction = new TransactionTemplate(transactionManager);
        String unique = UUID.randomUUID().toString().substring(0, 8);
        transaction.executeWithoutResult(status -> {
            AccessFixtures fixtures = new AccessFixtures(em);
            Organization org = fixtures.organization("Pre-migration " + unique);
            Employee person = fixtures.employee(org, "premigration-" + unique);
            organization = org.getId();
            employee = person.getId();
            user = person.getUser().getId();
            subject = person.getUser().getKeycloakId();
        });
    }

    @AfterEach
    void removeTheRows() {
        // The identity rows go with these through their foreign keys.
        jdbc.update("DELETE FROM employee WHERE id = ?", employee);
        jdbc.update("DELETE FROM users_table WHERE id = ?", user);
        jdbc.update("DELETE FROM organization WHERE id = ?", organization);
    }

    @Test
    void everyUserAndOrganizationGetsItsIdentityOnce() {
        assertThat(runner.runOnce()).isTrue();

        String linked = jdbc.queryForObject(
                "SELECT subject FROM user_identity WHERE user_id = ? AND provider = 'keycloak'", String.class, user);
        assertThat(linked).isEqualTo(subject);
        String group = jdbc.queryForObject(
                "SELECT provider_org_id FROM organization_identity WHERE organization_id = ? AND provider = 'keycloak'",
                String.class, organization);
        assertThat(group).isEqualTo("org-" + organization);
        assertThat(state.hasCompleted("keycloak-identity-backfill")).isTrue();

        Integer rows = identityRows();
        assertThat(runner.runOnce()).isTrue();
        assertThat(identityRows()).isEqualTo(rows);
    }

    @Test
    void aUserWithNoProviderAccountIsLeftOut() {
        jdbc.update("UPDATE users_table SET keycloak_id = NULL WHERE id = ?", user);

        assertThat(runner.runOnce()).isTrue();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_identity WHERE user_id = ?", Integer.class, user))
                .isZero();
    }

    private Integer identityRows() {
        return jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM user_identity WHERE user_id = ?)
                     + (SELECT count(*) FROM organization_identity WHERE organization_id = ?)
                """, Integer.class, user, organization);
    }

    @Test
    void aNullStatusBecomesActiveAndOtherStatusesStay() {
        Long left = transaction.execute(status -> {
            AccessFixtures fixtures = new AccessFixtures(em);
            Organization org = em.find(Organization.class, organization);
            return fixtures.employee(org, "left-" + UUID.randomUUID(), EmployeeStatus.resigned).getId();
        });
        jdbc.update("UPDATE employee SET status = NULL WHERE id = ?", employee);

        assertThat(runner.runOnce()).isTrue();

        assertThat(statusOf(employee)).isEqualTo("active");
        assertThat(statusOf(left)).isEqualTo("resigned");
        jdbc.update("DELETE FROM employee WHERE id = ?", left);
    }

    @Test
    void aRoleOnlyInKeycloakIsImportedOnce() {
        when(keycloakGroups.roleSubgroupMembers(Long.toString(organization)))
                .thenReturn(Map.of(OrgRole.HR_ADMIN, List.of(subject)));

        assertThat(runner.runOnce()).isTrue();
        assertThat(rolesOf(employee)).containsExactly("HR_ADMIN");

        assertThat(runner.runOnce()).isTrue();
        assertThat(rolesOf(employee)).containsExactly("HR_ADMIN");
        assertThat(state.hasCompleted(KeycloakRoleImport.NAME)).isTrue();
    }

    @Test
    void aRoleAlreadyInTheTableStaysAndARoleNotInKeycloakIsNotRemoved() {
        jdbc.update("INSERT INTO employee_org_roles (employee_id, role) VALUES (?, 'STORE_KEEPER')", employee);
        when(keycloakGroups.roleSubgroupMembers(Long.toString(organization)))
                .thenReturn(Map.of(OrgRole.HR_ADMIN, List.of(subject)));

        assertThat(runner.runOnce()).isTrue();

        assertThat(rolesOf(employee)).containsExactlyInAnyOrder("HR_ADMIN", "STORE_KEEPER");
    }

    @Test
    void aSubgroupMemberWithNoEmployeeHereIsLeftOut() {
        when(keycloakGroups.roleSubgroupMembers(Long.toString(organization)))
                .thenReturn(Map.of(OrgRole.OBSERVATION_PRODUCER, List.of("a-machine-login")));

        assertThat(runner.runOnce()).isTrue();

        assertThat(rolesOf(employee)).isEmpty();
        assertThat(state.hasCompleted(KeycloakRoleImport.NAME)).isTrue();
    }

    @Test
    void aGroupMemberWithNoEmployeeIsRecordedOnlyByTheFirstFinishedImport() {
        jdbc.update("DELETE FROM startup_task_state WHERE name = ?", KeycloakRoleImport.NAME);
        when(keycloakGroups.organizationGroupMembers(Long.toString(organization)))
                .thenReturn(List.of(subject, "a-bootstrap-administrator"));

        assertThat(runner.runOnce()).isTrue();
        assertThat(groupOnlyMembers()).containsExactly("a-bootstrap-administrator");

        when(keycloakGroups.organizationGroupMembers(Long.toString(organization)))
                .thenReturn(List.of(subject, "a-bootstrap-administrator", "someone-being-removed"));

        assertThat(runner.runOnce()).isTrue();
        assertThat(groupOnlyMembers()).containsExactly("a-bootstrap-administrator");
    }

    @Test
    void aRecordedAccountThatGetsAnEmployeeStopsBeingRecorded() {
        assertThat(runner.runOnce()).isTrue();
        jdbc.update("INSERT INTO keycloak_group_only_member (subject, organization_id) VALUES (?, ?)",
                subject, organization);

        assertThat(runner.runOnce()).isTrue();

        assertThat(groupOnlyMembers()).isEmpty();
    }

    @Test
    void keycloakFailingLeavesTheImportUnrecordedAndTheOtherTasksDone() {
        jdbc.update("DELETE FROM startup_task_state WHERE name = ?", KeycloakRoleImport.NAME);
        when(keycloakGroups.roleSubgroupMembers(Long.toString(organization)))
                .thenThrow(new IllegalStateException("Keycloak is down"));

        assertThat(runner.runOnce()).isTrue();

        assertThat(state.hasCompleted(KeycloakRoleImport.NAME)).isFalse();
        assertThat(state.hasCompleted("keycloak-identity-backfill")).isTrue();
        assertThat(state.hasCompleted("null-employee-status-cleanup")).isTrue();

        reset(keycloakGroups);
        assertThat(runner.runOnce()).isTrue();
        assertThat(state.hasCompleted(KeycloakRoleImport.NAME)).isTrue();
    }

    private List<String> groupOnlyMembers() {
        return jdbc.queryForList("SELECT subject FROM keycloak_group_only_member WHERE organization_id = ?",
                String.class, organization);
    }

    private String statusOf(Long id) {
        return jdbc.queryForObject("SELECT status FROM employee WHERE id = ?", String.class, id);
    }

    private List<String> rolesOf(Long id) {
        return jdbc.queryForList("SELECT role FROM employee_org_roles WHERE employee_id = ?", String.class, id);
    }
}
