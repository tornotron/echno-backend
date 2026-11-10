package org.tornotron.echno_backend.modules.access.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.modules.access.matrix.assignment.RoleAssignmentITSupport;
import org.tornotron.echno_backend.modules.access.matrix.domain.AccessRole;

/**
 * {@code EmployeeDto.orgRoles} and {@code GET /employees/{id}/org-roles}, now read from the role
 * assignments, against the {@code employee_org_roles} rows they used to copy: on an organization shaped
 * like the QA seed, and across a change made in the same transaction.
 */
class OrgRolesFieldEquivalenceIT extends RoleAssignmentITSupport {

    @Autowired
    private EmployeeMapper mapper;

    private final List<Employee> people = new ArrayList<>();

    @BeforeEach
    void aSeedShapedOrganization() {
        reconciler.ensureStartingRoles(organization.getId());
        for (OrgRole role : OrgRole.values()) {
            Employee person = employee("field-" + role.getGroupName());
            assignments.assignOrgRoleAsSystem(person.getId(), role);
            people.add(person);
        }
        for (String slug : List.of("management-director", "procurement-manager", "finance-manager",
                "client-owner", "detection-reviewer")) {
            Employee person = employee("field-" + slug);
            assignments.assignAsSystem(person.getId(), roleId(slug), null);
            people.add(person);
        }
        Employee several = employee("field-several");
        for (OrgRole role : List.of(OrgRole.SYSTEM_ADMIN, OrgRole.HR_ADMIN, OrgRole.SITE_ENGINEER)) {
            assignments.assignOrgRoleAsSystem(several.getId(), role);
        }
        people.add(several);
        Employee custom = employee("field-custom");
        AccessRole made = fixtures.newRole(organization, "crew-lead");
        made.setCreatedBy(custom.getId());
        em.persist(made);
        fixtures.assign(organization, custom, made, null);
        people.add(custom);
        people.add(employee("field-baseline"));
        flush();
    }

    @Test
    void everyonesFieldIsWhatTheOldRowsSaid() {
        for (Employee person : people) {
            Set<OrgRole> before = oldRowsOf(person);

            assertThat(mapper.toDto(person).getOrgRoles()).as(person.getEmployeeName()).isEqualTo(before);
            assertThat(employeeService.getOrgRoles(person.getId())).as(person.getEmployeeName()).isEqualTo(before);
        }
        assertThat(oldRowsOf(people.get(people.size() - 3)))
                .containsExactlyInAnyOrder(OrgRole.SYSTEM_ADMIN, OrgRole.HR_ADMIN, OrgRole.SITE_ENGINEER);
    }

    @Test
    void aChangeInTheSameTransactionShowsInTheNextRead() {
        Employee person = people.get(people.size() - 1);
        assertThat(mapper.toDto(person).getOrgRoles()).isEmpty();

        assertThat(employeeService.assignOrgRoleAsSystem(person.getId(), OrgRole.STORE_KEEPER).getOrgRoles())
                .containsExactly(OrgRole.STORE_KEEPER);
        assertThat(mapper.toDto(person).getOrgRoles()).isEqualTo(oldRowsOf(person));

        administrator("field-admin");
        assertThat(employeeService.removeOrgRole(person.getId(), OrgRole.STORE_KEEPER).getOrgRoles()).isEmpty();
        assertThat(oldRowsOf(person)).isEmpty();
    }

    private Set<OrgRole> oldRowsOf(Employee person) {
        return legacyRowsOf(person).stream().map(OrgRole::valueOf).collect(Collectors.toSet());
    }
}
