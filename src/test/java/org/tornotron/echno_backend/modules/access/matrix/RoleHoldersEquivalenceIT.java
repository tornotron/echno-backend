package org.tornotron.echno_backend.modules.access.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeHierarchyService;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.leave.enums.LeaveApproverRole;
import org.tornotron.echno_backend.modules.access.api.AccessRoleHolders;
import org.tornotron.echno_backend.modules.access.matrix.assignment.RoleAssignmentITSupport;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;

/**
 * The role-name queries against the role holders that replaced them, on an organization shaped like
 * the QA seed: one person per old role, the starting roles with no old counterpart, someone holding two
 * roles, a holder who has since left, a role the organization made itself, two projects with different
 * members, and a second organization alongside. Every query answers the same people as the old one read
 * from {@code employee_org_roles}.
 */
class RoleHoldersEquivalenceIT extends RoleAssignmentITSupport {

    private static final String OLD_ACROSS = """
            SELECT DISTINCT e.id FROM employee e JOIN employee_org_roles o ON o.employee_id = e.id
            WHERE e.organization_id = ? AND o.role = ? ORDER BY e.id
            """;

    private static final String OLD_ON_PROJECT = """
            SELECT DISTINCT e.id FROM employee e JOIN employee_org_roles o ON o.employee_id = e.id
            JOIN project_employees pe ON pe.employee_id = e.id
            WHERE e.organization_id = ? AND pe.project_id = ? AND o.role = ? ORDER BY e.id
            """;

    @Autowired
    private AccessRoleHolders holders;

    @Autowired
    private EmployeeHierarchyService hierarchy;

    private Project siteA;
    private Project siteB;

    @BeforeEach
    void aSeedShapedOrganization() {
        reconciler.ensureStartingRoles(organization.getId());
        siteA = fixtures.project(organization, "Harbour View " + System.nanoTime());
        siteB = fixtures.project(organization, "Lakeside Court " + System.nanoTime());
        List<Employee> everyone = new ArrayList<>();
        for (OrgRole role : OrgRole.values()) {
            Employee person = employee("holders-" + role.getGroupName());
            assignments.assignOrgRoleAsSystem(person.getId(), role);
            everyone.add(person);
        }
        for (String slug : List.of("management-director", "procurement-manager", "finance-manager",
                "client-owner", "detection-reviewer")) {
            Employee person = employee("holders-" + slug);
            assignments.assignAsSystem(person.getId(), roleId(slug), null);
            everyone.add(person);
        }
        Employee twoRoles = employee("holders-hr-and-pm");
        assignments.assignOrgRoleAsSystem(twoRoles.getId(), OrgRole.HR_ADMIN);
        assignments.assignOrgRoleAsSystem(twoRoles.getId(), OrgRole.PROJECT_MANAGER);
        Employee leaver = employee("holders-left-store-keeper");
        assignments.assignOrgRoleAsSystem(leaver.getId(), OrgRole.STORE_KEEPER);
        leaver.setStatus(EmployeeStatus.terminated);
        Employee custom = employee("holders-custom");
        AccessRole made = fixtures.newRole(organization, "site-lead");
        made.setCreatedBy(custom.getId());
        em.persist(made);
        fixtures.assign(organization, custom, made, null);
        employee("holders-baseline");

        // Site A has everyone; site B a project manager, the two-role holder and the leaver, no store keeper.
        everyone.forEach(siteA.getEmployees()::add);
        siteA.getEmployees().addAll(List.of(twoRoles, leaver, custom));
        siteB.getEmployees().add(everyone.get(OrgRole.PROJECT_MANAGER.ordinal()));
        siteB.getEmployees().addAll(List.of(twoRoles, leaver));

        Organization other = fixtures.organization("Holders Elsewhere " + System.nanoTime());
        reconciler.ensureStartingRoles(other.getId());
        Employee elsewhere = fixtures.employee(other, "holders-elsewhere-" + System.nanoTime());
        flush();
        jdbc.update("INSERT INTO access_employee_role (id, organization_id, employee_id, role_id, created_at) "
                        + "SELECT gen_random_uuid(), ?, ?, id, now() FROM access_role WHERE organization_id = ? "
                        + "AND slug IN ('project-manager', 'store-keeper', 'system-admin')",
                other.getId(), elsewhere.getId(), other.getId());
        jdbc.update("INSERT INTO employee_org_roles (employee_id, role) VALUES (?, 'PROJECT_MANAGER'), "
                + "(?, 'STORE_KEEPER'), (?, 'SYSTEM_ADMIN')", elsewhere.getId(), elsewhere.getId(), elsewhere.getId());
        flush();
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void theHoldersOfEachStartingRoleAreTheHoldersOfItsOldRole(OrgRole role) {
        List<Long> before = jdbc.queryForList(OLD_ACROSS, Long.class, organization.getId(), role.name());

        assertThat(before).isNotEmpty();
        assertThat(holders.acrossOrganization(organization.getId(), List.of(slugOf(role)))).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(OrgRole.class)
    void theHoldersOnEachProjectAreTheOldRolesHoldersOnIt(OrgRole role) {
        for (Project site : List.of(siteA, siteB)) {
            List<Long> before = jdbc.queryForList(OLD_ON_PROJECT, Long.class, organization.getId(), site.getId(),
                    role.name());

            assertThat(holders.onProject(organization.getId(), site.getId(), slugOf(role)))
                    .as("%s on %s", role, site.getProjectName()).isEqualTo(before);
        }
    }

    @Test
    void theNoticeRecipientsOfEachProjectAreTheOldOnes() {
        // DocumentReversalService.notifyStores and LowStockSweep.recipientsFor: the store keepers, else
        // the project managers. Site B has no store keeper but the one who left.
        for (Project site : List.of(siteA, siteB)) {
            List<Long> before = jdbc.queryForList(OLD_ON_PROJECT, Long.class, organization.getId(), site.getId(),
                    OrgRole.STORE_KEEPER.name());
            List<Long> after = holders.onProject(organization.getId(), site.getId(), AccessRoleHolders.STORE_KEEPER);
            assertThat(after).isEqualTo(before).isNotEmpty();
        }
        List<Long> managersOnB = jdbc.queryForList(OLD_ON_PROJECT, Long.class, organization.getId(), siteB.getId(),
                OrgRole.PROJECT_MANAGER.name());
        assertThat(holders.onProject(organization.getId(), siteB.getId(), AccessRoleHolders.PROJECT_MANAGER))
                .isEqualTo(managersOnB).hasSize(2);
    }

    @Test
    void theManagerListingIsTheOldOne() {
        List<Long> before = jdbc.queryForList("""
                SELECT DISTINCT e.id FROM employee e JOIN employee_org_roles o ON o.employee_id = e.id
                WHERE e.organization_id = ? AND o.role IN ('SYSTEM_ADMIN', 'ORG_MANAGER', 'HR_ADMIN', 'PROJECT_MANAGER')
                ORDER BY e.id
                """, Long.class, organization.getId());
        Set<String> oldManagerRoles = OrgRole.getManagerRoles().stream().map(OrgRole::name).collect(Collectors.toSet());
        assertThat(oldManagerRoles).containsExactlyInAnyOrder("SYSTEM_ADMIN", "ORG_MANAGER", "HR_ADMIN",
                "PROJECT_MANAGER");

        List<Long> after = hierarchy.readAllTheManagersByOrganizationId(organization.getId()).stream()
                .map(EmployeeDto::getId).toList();

        assertThat(after).isEqualTo(before).hasSize(5);
    }

    @ParameterizedTest
    @EnumSource(value = LeaveApproverRole.class, names = {"HR_ADMIN", "SYSTEM_ADMIN"})
    void eachLeaveTierNamesTheHoldersOfItsOldRole(LeaveApproverRole tier) {
        // LeaveApproverRole used to carry OrgRole.HR_ADMIN and OrgRole.SYSTEM_ADMIN.
        List<Long> before = jdbc.queryForList(OLD_ACROSS, Long.class, organization.getId(), tier.name());

        assertThat(holders.acrossOrganization(organization.getId(), List.of(tier.getStartingRole())))
                .isEqualTo(before).isNotEmpty();
    }

    @Test
    void theManagementLineTierNamesNoRole() {
        assertThat(LeaveApproverRole.REPORTING_MANAGER.getStartingRole()).isNull();
    }

    /** The template that stands in for the old role. */
    private String slugOf(OrgRole role) {
        return jdbc.queryForObject("SELECT slug FROM access_role_template WHERE legacy_org_role = ?", String.class,
                role.getGroupName());
    }
}
