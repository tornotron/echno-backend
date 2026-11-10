package org.tornotron.echno_backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
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
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
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
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.modules.access.matrix.repository.AccessFactsQueries;
import org.tornotron.echno_backend.modules.access.matrix.service.AccessReconcileReport;
import org.tornotron.echno_backend.modules.access.matrix.service.AccessReconcileReport.MemberDifference;
import org.tornotron.echno_backend.modules.access.matrix.service.AccessRoleBackfill;
import org.tornotron.echno_backend.modules.access.matrix.service.AccessRoleReconciler;
import org.tornotron.echno_backend.modules.access.matrix.service.CachedAccessFactsSource;
import org.tornotron.echno_backend.modules.access.matrix.service.DatabaseAccessFactsSource;
import org.tornotron.echno_backend.modules.access.matrix.service.MatrixAccessService;
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
        KeycloakTokenMapper.class, TransactionRetryTemplate.class, TransactionalWorkRunner.class,
        AccessRoleBackfill.class, AccessRoleReconciler.class, AccessReconcileReport.class, CachedAccessFactsSource.class,
        DatabaseAccessFactsSource.class, AccessFactsQueries.class, ConcurrentMapCacheManager.class})
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
    private CachedAccessFactsSource facts;

    @Autowired
    private StartupTaskRunner runner;

    @Autowired
    private StartupTaskState state;

    @Autowired
    private AccessRoleBackfill backfill;

    private final List<Long> others = new ArrayList<>();

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
        for (Long other : others) {
            Long otherUser = jdbc.queryForObject("SELECT user_id FROM employee WHERE id = ?", Long.class, other);
            jdbc.update("DELETE FROM employee WHERE id = ?", other);
            jdbc.update("DELETE FROM users_table WHERE id = ?", otherUser);
        }
        others.clear();
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

    @Test
    void everyOldRoleBecomesAnAssignmentAndTheEngineAgrees() {
        jdbc.update("INSERT INTO employee_org_roles (employee_id, role) VALUES (?, 'STORE_KEEPER')", employee);

        assertThat(runner.runOnce()).isTrue();

        AccessService engine = new MatrixAccessService(facts, area -> true);
        AccessSubject subject = new AccessSubject(organization, employee, user);
        // A store keeper today: inventory, and reads across stores and buying.
        assertThat(engine.check(subject, AccessPermission.of("inventory:edit"))).isTrue();
        assertThat(engine.check(subject, AccessPermission.of("vendors:read"))).isTrue();
        // Not a store keeper's: anything of a project manager's or an administrator's.
        assertThat(engine.check(subject, AccessPermission.of("vendors:delete"))).isFalse();
        assertThat(engine.check(subject, AccessPermission.of("finance:edit"))).isFalse();
        assertThat(engine.check(subject, AccessPermission.of("access:edit"))).isFalse();
        // What every member reached: the baseline.
        assertThat(engine.check(subject, AccessPermission.of("chat:read"))).isTrue();
        assertThat(engine.check(subject, AccessPermission.of("documents:delete"))).isTrue();
    }

    @Test
    void everyOldRoleNameHasARoleToLandOn() {
        assertThat(runner.runOnce()).isTrue();

        for (OrgRole role : OrgRole.values()) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM access_role WHERE organization_id = ? AND legacy_org_role = ?",
                    Integer.class, organization, role.getGroupName()))
                    .as(role.name()).isEqualTo(1);
        }
    }

    @Test
    void anOrganizationGetsEveryTemplateOnceAndARerunChangesNothing() {
        assertThat(runner.runOnce()).isTrue();

        assertThat(rolesInOrganization()).isEqualTo(15);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM access_role WHERE organization_id = ? AND is_system",
                Integer.class, organization)).isEqualTo(2);
        Integer grants = grantsInOrganization();
        assertThat(grants).isPositive();

        assertThat(runner.runOnce()).isTrue();

        assertThat(rolesInOrganization()).isEqualTo(15);
        assertThat(grantsInOrganization()).isEqualTo(grants);
    }

    @Test
    void aTemplateChangeReachesAnUncustomizedRoleAndNotACustomizedOne() {
        assertThat(runner.runOnce()).isTrue();
        jdbc.update("UPDATE access_role SET customized = true WHERE organization_id = ? AND slug = 'store-keeper'",
                organization);
        jdbc.update("DELETE FROM access_role_grant WHERE role_id = (SELECT id FROM access_role "
                + "WHERE organization_id = ? AND slug = 'store-keeper') AND permission_key = 'vendors:read'",
                organization);
        UUID added = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO access_role_template_grant (id, template_id, permission_key, scope)
                SELECT ?, id, 'assets:create', 'ALL' FROM access_role_template WHERE slug = 'site-engineer'
                """, added);
        try {
            assertThat(runner.runOnce()).isTrue();

            assertThat(holds("site-engineer", "assets:create")).isTrue();
            assertThat(holds("store-keeper", "vendors:read")).isFalse();
        } finally {
            jdbc.update("DELETE FROM access_role_template_grant WHERE id = ?", added);
        }

        assertThat(runner.runOnce()).isTrue();
        assertThat(holds("site-engineer", "assets:create")).isFalse();
    }

    @Test
    void aRoleAlreadyNamedLikeATemplateBlocksOnlyThatTemplate() {
        jdbc.update("""
                INSERT INTO access_role (id, organization_id, slug, name, is_system, is_baseline, created_at, updated_at)
                VALUES (gen_random_uuid(), ?, 'old-custom', 'Store Keeper', false, false, now(), now())
                """, organization);

        assertThat(runner.runOnce()).isTrue();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM access_role WHERE organization_id = ? AND slug = 'store-keeper'",
                Integer.class, organization)).isZero();
        assertThat(rolesInOrganization()).isEqualTo(15);
    }

    @Test
    void anAdministratorMayGrantEveryRoleAndHrAdminAllButAdministrator() {
        assertThat(runner.runOnce()).isTrue();

        // The detection reviewer is for reviewers outside the client's staff: no starting role gives it.
        assertThat(grantable("system-admin")).contains("system-admin", "project-manager", "hr-admin")
                .doesNotContain("detection-reviewer").hasSize(13);
        assertThat(grantable("hr-admin")).contains("project-manager", "hr-admin")
                .doesNotContain("system-admin").hasSize(12);
        assertThat(grantable("project-manager")).isEmpty();
    }

    @Test
    void anOrganizationWithEveryOldRoleAndSomePairsEndsWithTodaysAccess() {
        // Shaped like the QA seed: one person per old role, people holding two, and one holding none.
        for (OrgRole role : OrgRole.values()) {
            withOldRoles(role.name());
        }
        withOldRoles("SYSTEM_ADMIN", "PROJECT_MANAGER");
        withOldRoles("HR_ADMIN", "STORE_KEEPER");
        withOldRoles("SITE_ENGINEER", "QA_ENGINEER", "SAFETY_OFFICER");

        assertThat(runner.runOnce()).isTrue();
        AccessRoleBackfill.Result first = backfill.reconcile(List.of(organization), false);
        AccessRoleBackfill.Result again = backfill.reconcile(List.of(organization), false);

        assertThat(first.failed()).isEmpty();
        assertThat(first.differences().get(organization)).isEmpty();
        assertThat(again.differences().get(organization)).isEmpty();
        assertThat(first.withoutRoles()).doesNotContain(organization);
    }

    @Test
    void theReportNamesAMemberWhoseRoleNoLongerGivesTodaysAccess() {
        jdbc.update("INSERT INTO employee_org_roles (employee_id, role) VALUES (?, 'STORE_KEEPER')", employee);
        assertThat(runner.runOnce()).isTrue();
        jdbc.update("UPDATE access_role SET customized = true WHERE organization_id = ? AND slug = 'store-keeper'",
                organization);
        jdbc.update("DELETE FROM access_role_grant WHERE role_id = (SELECT id FROM access_role "
                + "WHERE organization_id = ? AND slug = 'store-keeper') AND permission_key = 'vendors:read'",
                organization);

        List<MemberDifference> found = backfill.reconcile(List.of(organization), false).differences().get(organization);

        assertThat(found).singleElement().satisfies(member -> {
            assertThat(member.employeeId()).isEqualTo(employee);
            assertThat(member.missing()).containsExactly("vendors:read/ALL");
            assertThat(member.extra()).isEmpty();
        });
    }

    @Test
    void aCustomizedRoleTakesEachTemplateChangeOnceAndKeepsWhatTheOrganizationTookOff() {
        assertThat(runner.runOnce()).isTrue();
        jdbc.update("UPDATE access_role SET customized = true WHERE organization_id = ? AND slug = 'project-manager'",
                organization);
        // The organization takes one starting grant off.
        takeOff("project-manager", "assets:delete");
        // A later changeset adds a grant to keep today's access for an area moving onto the matrix.
        UUID added = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO access_role_template_grant (id, template_id, permission_key, scope)
                SELECT ?, id, 'labour:delete', 'ALL' FROM access_role_template WHERE slug = 'project-manager'
                """, added);
        try {
            assertThat(runner.runOnce()).isTrue();
            assertThat(holds("project-manager", "labour:delete")).isTrue();
            assertThat(holds("project-manager", "assets:delete")).isFalse();

            // Once given, the organization's choice wins: taken off, it stays off.
            takeOff("project-manager", "labour:delete");
            assertThat(runner.runOnce()).isTrue();
            assertThat(holds("project-manager", "labour:delete")).isFalse();

            // Given back, and then dropped from the template, it leaves the customized role too.
            jdbc.update("""
                    INSERT INTO access_role_grant (id, role_id, permission_key, scope)
                    SELECT gen_random_uuid(), id, 'labour:delete', 'ALL' FROM access_role
                    WHERE organization_id = ? AND slug = 'project-manager'
                    """, organization);
        } finally {
            jdbc.update("DELETE FROM access_role_template_grant WHERE id = ?", added);
        }
        assertThat(runner.runOnce()).isTrue();
        assertThat(holds("project-manager", "labour:delete")).isFalse();
        assertThat(holds("project-manager", "assets:delete")).isFalse();
        assertThat(holds("project-manager", "assets:edit")).isTrue();
    }

    @Test
    void aCustomizedRoleFollowsATemplateRescope() {
        assertThat(runner.runOnce()).isTrue();
        jdbc.update("UPDATE access_role SET customized = true WHERE organization_id = ? AND is_baseline",
                organization);
        UUID wide = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO access_role_template_grant (id, template_id, permission_key, scope)
                SELECT ?, id, 'assets:create', 'ALL' FROM access_role_template WHERE is_baseline
                """, wide);
        try {
            assertThat(runner.runOnce()).isTrue();
            assertThat(scopesOn("employee", "assets:create")).containsExactly("ALL");

            jdbc.update("UPDATE access_role_template_grant SET scope = 'OWN' WHERE id = ?", wide);
            assertThat(runner.runOnce()).isTrue();
            assertThat(scopesOn("employee", "assets:create")).containsExactly("OWN");
        } finally {
            jdbc.update("DELETE FROM access_role_template_grant WHERE id = ?", wide);
        }
        assertThat(runner.runOnce()).isTrue();
        assertThat(scopesOn("employee", "assets:create")).isEmpty();
    }

    @Test
    void aRoleTheOrganizationMadeNeverFollowsATemplateThatTakesItsSlug() {
        jdbc.update("""
                INSERT INTO access_role (id, organization_id, slug, name, is_system, is_baseline, customized,
                                         created_by, created_at, updated_at)
                VALUES (gen_random_uuid(), ?, 'site-engineer', 'Our Site Engineer', false, false, true, ?, now(), now())
                """, organization, user);

        assertThat(runner.runOnce()).isTrue();
        assertThat(runner.runOnce()).isTrue();

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM access_role_grant g JOIN access_role r ON r.id = g.role_id
                WHERE r.organization_id = ? AND r.slug = 'site-engineer'
                """, Integer.class, organization)).isZero();
    }

    @Test
    void aDryRunReportsAndKeepsNothing() {
        jdbc.update("INSERT INTO employee_org_roles (employee_id, role) VALUES (?, 'HR_ADMIN')", employee);

        AccessRoleBackfill.Result dry = backfill.reconcile(List.of(organization), true);

        assertThat(dry.withoutRoles()).contains(organization);
        assertThat(dry.differences().get(organization)).isEmpty();
        assertThat(rolesInOrganization()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM access_employee_role WHERE employee_id = ?",
                Integer.class, employee)).isZero();
    }

    private void takeOff(String slug, String key) {
        jdbc.update("DELETE FROM access_role_grant WHERE role_id = (SELECT id FROM access_role "
                + "WHERE organization_id = ? AND slug = ?) AND permission_key = ?", organization, slug, key);
    }

    private void withOldRoles(String... roles) {
        Long person = transaction.execute(status -> {
            AccessFixtures fixtures = new AccessFixtures(em);
            Organization org = em.find(Organization.class, organization);
            return fixtures.employee(org, "seeded-" + UUID.randomUUID()).getId();
        });
        others.add(person);
        for (String role : roles) {
            jdbc.update("INSERT INTO employee_org_roles (employee_id, role) VALUES (?, ?)", person, role);
        }
    }

    private List<String> scopesOn(String slug, String key) {
        return jdbc.queryForList("""
                SELECT g.scope FROM access_role_grant g JOIN access_role r ON r.id = g.role_id
                WHERE r.organization_id = ? AND r.slug = ? AND g.permission_key = ?
                """, String.class, organization, slug, key);
    }

    private boolean holds(String slug, String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT count(*) > 0 FROM access_role_grant g JOIN access_role r ON r.id = g.role_id
                WHERE r.organization_id = ? AND r.slug = ? AND g.permission_key = ?
                """, Boolean.class, organization, slug, key));
    }

    private List<String> grantable(String slug) {
        return jdbc.queryForList("""
                SELECT target.slug FROM access_role_grantable g
                JOIN access_role r ON r.id = g.role_id
                JOIN access_role target ON target.id = g.grantable_role_id
                WHERE g.organization_id = ? AND r.slug = ?
                """, String.class, organization, slug);
    }

    private Integer rolesInOrganization() {
        return jdbc.queryForObject("SELECT count(*) FROM access_role WHERE organization_id = ?",
                Integer.class, organization);
    }

    private Integer grantsInOrganization() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM access_role_grant g JOIN access_role r ON r.id = g.role_id
                WHERE r.organization_id = ?
                """, Integer.class, organization);
    }
}
