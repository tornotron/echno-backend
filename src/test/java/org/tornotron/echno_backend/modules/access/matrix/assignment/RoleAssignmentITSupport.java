package org.tornotron.echno_backend.modules.access.matrix.assignment;

import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.tornotron.echno_backend.attendance.mapper.ShiftTimingMapperImpl;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.identity.IdentityCapabilities;
import org.tornotron.echno_backend.common.identity.IdentityProvider;
import org.tornotron.echno_backend.common.identity.task.IdentityTaskQueue;
import org.tornotron.echno_backend.common.mapper.AttachmentMapperImpl;
import org.tornotron.echno_backend.common.module.ModuleRegistry;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.TeamAccess;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeHierarchyService;
import org.tornotron.echno_backend.employee.EmployeeService;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapperImpl;
import org.tornotron.echno_backend.modules.access.AccessControlModule;
import org.tornotron.echno_backend.modules.access.api.ScopeAwareArea;
import org.tornotron.echno_backend.modules.access.guard.CurrentAccessSubject;
import org.tornotron.echno_backend.modules.access.guard.EnforcedPermissions;
import org.tornotron.echno_backend.modules.access.matrix.AccessFixtures;
import org.tornotron.echno_backend.modules.access.matrix.catalog.AccessCatalogReader;
import org.tornotron.echno_backend.modules.access.matrix.catalog.ScopeAwareAreas;
import org.tornotron.echno_backend.modules.access.matrix.repository.AccessFactsQueries;
import org.tornotron.echno_backend.modules.access.matrix.service.AccessChangeRecorder;
import org.tornotron.echno_backend.modules.access.matrix.service.AccessRoleReconciler;
import org.tornotron.echno_backend.modules.access.matrix.service.CachedAccessFactsSource;
import org.tornotron.echno_backend.modules.access.matrix.service.DatabaseAccessFactsSource;
import org.tornotron.echno_backend.modules.access.matrix.service.LegacyRoleMirror;
import org.tornotron.echno_backend.modules.access.api.AccessRoleAssignments;
import org.tornotron.echno_backend.modules.access.matrix.service.MatrixAccessService;
import org.tornotron.echno_backend.modules.access.matrix.service.MatrixRoleAssignments;
import org.tornotron.echno_backend.modules.access.web.AccessAdminWriter;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionModule;
import org.tornotron.echno_backend.modules.inspections.InspectionsModule;
import org.tornotron.echno_backend.modules.vision.VisionModule;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * The real assignment writer, the Roles &amp; Permissions writer and {@code EmployeeService}, on
 * CockroachDB, as a caller in one organization.
 *
 * <p>No area is converted yet, so {@link Areas} carries the guards that make the permissions these
 * tests grant enforced, and registers {@code assets} as an area that filters by scope.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({IdentityTaskQueue.class, MatrixRoleAssignments.class, LegacyRoleMirror.class, AccessRoleReconciler.class,
        CachedAccessFactsSource.class, DatabaseAccessFactsSource.class, AccessFactsQueries.class,
        ConcurrentMapCacheManager.class, CurrentEmployeeService.class, UserContextService.class,
        EmployeeService.class, TeamAccess.class, EmployeeHierarchyService.class, EmployeeMapperImpl.class,
        AttachmentMapperImpl.class, ShiftTimingMapperImpl.class, OrganizationSecurityService.class,
        MatrixAccessService.class, CurrentAccessSubject.class, AccessChangeRecorder.class, AccessAdminWriter.class,
        AccessCatalogReader.class, EnforcedPermissions.class, ScopeAwareAreas.class, RoleAssignmentITSupport.Areas.class})
public abstract class RoleAssignmentITSupport extends AbstractIntegrationTest {

    /** The area these tests register as filtering by scope. */
    protected static final String SCOPE_AWARE_AREA = "assets";

    @TestConfiguration
    static class Areas {

        // The modules whose areas the starting roles hold grants on, so a role's held grants are in the
        // catalog when a test sends them back. Vision needs the inspections module it depends on.
        @Bean
        ModuleRegistry moduleRegistry() {
            return new ModuleRegistry(List.of(new AccessControlModule(),
                    new DocumentIngestionModule(new MockEnvironment()), new InspectionsModule(),
                    new VisionModule()),
                    (organization, key) -> true,
                    new MockEnvironment());
        }

        @Bean
        ScopeAwareArea scopeAwareAssets() {
            return () -> SCOPE_AWARE_AREA;
        }

        @Bean
        Guards guards() {
            return new Guards();
        }
    }

    /** Stands in for the converted areas: each guard makes its permission enforced. */
    static class Guards {

        @PreAuthorize("@access.can('access', 'read') or @access.can('access', 'edit')"
                + " or @access.can('access', 'edit-employee-role')")
        public void access() {
        }

        @PreAuthorize("@access.can('finance', 'read') or @access.can('finance', 'edit')")
        public void finance() {
        }

        @PreAuthorize("@access.can('assets', 'read') or @access.can('assets', 'edit')")
        public void assets() {
        }
    }

    @Autowired
    protected jakarta.persistence.EntityManager em;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected EmployeeService employeeService;

    @Autowired
    protected AccessRoleAssignments assignments;

    @Autowired
    protected AccessRoleReconciler reconciler;

    @Autowired
    protected AccessAdminWriter writer;

    @MockitoBean
    protected IdentityProvider identityProvider;

    @MockitoBean
    protected FileStorageService fileStorageService;

    protected AccessFixtures fixtures;
    protected Organization organization;

    @BeforeEach
    protected void anOrganization() {
        when(identityProvider.capabilities()).thenReturn(IdentityCapabilities.none());
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;  // A test that commits sets up its own rows.
        }
        fixtures = new AccessFixtures(em);
        organization = fixtures.organization("Assignments " + System.nanoTime());
        em.flush();
        TenantContext.setCurrentOrgId(organization.getId());
    }

    @AfterEach
    protected void noCallerLeftBehind() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    protected Employee employee(String name) {
        Employee person = fixtures.employee(organization, name + "-" + System.nanoTime(), EmployeeStatus.active);
        em.flush();
        return person;
    }

    /** Makes this person the caller, holding these old roles the way the converter would mint them. */
    protected void signInAs(Employee person, String... legacyRoles) {
        List<SimpleGrantedAuthority> authorities = Arrays.stream(legacyRoles)
                .map(role -> new SimpleGrantedAuthority("ORG_" + organization.getId() + "_ROLE_" + role))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                person.getUser().getKeycloakId(), null, authorities));
    }

    protected void signInAsSomeoneWithNoEmployeeHolding(String legacyRole) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "bootstrap-subject-" + System.nanoTime(), null,
                List.of(new SimpleGrantedAuthority("ORG_" + organization.getId() + "_ROLE_" + legacyRole))));
    }

    protected void flush() {
        em.flush();
    }

    protected List<String> legacyRowsOf(Employee person) {
        flush();
        return jdbc.queryForList("SELECT role FROM employee_org_roles WHERE employee_id = ?", String.class, person.getId());
    }

    protected List<String> assignmentsOf(Employee person) {
        flush();
        return jdbc.queryForList("""
                SELECT r.slug FROM access_employee_role a JOIN access_role r ON r.id = a.role_id
                WHERE a.employee_id = ?
                """, String.class, person.getId());
    }

    protected List<String> logActionsAbout(Employee person) {
        flush();
        return jdbc.queryForList("""
                SELECT action FROM access_change_log
                WHERE organization_id = ? AND target_type = 'employee' AND target_id = ?
                ORDER BY changed_at
                """, String.class, organization.getId(), person.getId().toString());
    }

    /** A person made the organization's System Admin by the system, and signed in as the caller. */
    protected Employee administrator(String name) {
        Employee admin = employee(name);
        employeeService.assignOrgRoleAsSystem(admin.getId(), OrgRole.SYSTEM_ADMIN);
        flush();
        signInAs(admin, "system-admin");
        return admin;
    }

    protected UUID roleId(String slug) {
        flush();
        return jdbc.queryForObject("SELECT id FROM access_role WHERE organization_id = ? AND slug = ?",
                UUID.class, organization.getId(), slug);
    }

    /** A role's grants, each written {@code <permission> <scope>}. */
    protected List<String> grantsOf(UUID role) {
        flush();
        return jdbc.queryForList("SELECT permission_key || ' ' || scope FROM access_role_grant WHERE role_id = ? "
                + "ORDER BY permission_key, scope", String.class, role);
    }

    protected List<String> logActionsAboutRole(UUID role) {
        flush();
        return jdbc.queryForList("""
                SELECT action FROM access_change_log
                WHERE organization_id = ? AND target_type = 'role' AND target_id = ?
                ORDER BY changed_at
                """, String.class, organization.getId(), role.toString());
    }
}
