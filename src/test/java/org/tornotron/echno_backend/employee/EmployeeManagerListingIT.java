package org.tornotron.echno_backend.employee;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.tornotron.echno_backend.attendance.mapper.ShiftTimingMapper;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.mapper.AttachmentMapperImpl;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapperImpl;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the manager listings, against a real CockroachDB.
 *
 * <p>Regression guard for the org-scoped listing. It used to read an {@code is_manager}
 * boolean on the employee row, which nothing ever wrote: on staging it was false on all
 * thirty rows while five people held a manager role, so the endpoint returned an empty
 * list for every organization while the unscoped listing beside it returned the right
 * people. These tests seed exactly that shape, role holders and no flag, so they fail
 * against the old query and pass against the role-based one.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({EmployeeHierarchyService.class, EmployeeMapperImpl.class,
        AttachmentMapperImpl.class, ShiftTimingMapper.class})
class EmployeeManagerListingIT extends AbstractIntegrationTest {

    @Autowired
    private EmployeeHierarchyService hierarchyService;

    @Autowired
    private TestEntityManager em;

    @MockitoBean
    private FileStorageService fileStorageService;

    @Test
    void managersForAnOrganization_areTheRoleHolders() {
        Organization org = persistOrganization("Role Org");
        persistEmployee(org, "Priya Manager", Set.of(OrgRole.PROJECT_MANAGER));
        persistEmployee(org, "Sam Admin", Set.of(OrgRole.SYSTEM_ADMIN));
        persistEmployee(org, "Ravi Worker", Set.of());
        em.flush();
        em.clear();

        List<EmployeeDto> managers = hierarchyService.readAllTheManagersByOrganizationId(org.getId());

        assertThat(managers).extracting(EmployeeDto::getEmployeeName)
                .containsExactlyInAnyOrder("Priya Manager", "Sam Admin");
    }

    @Test
    void managersForAnOrganization_excludeAnotherOrganizationsManagers() {
        Organization mine = persistOrganization("Mine");
        Organization theirs = persistOrganization("Theirs");
        persistEmployee(mine, "My Manager", Set.of(OrgRole.HR_ADMIN));
        persistEmployee(theirs, "Their Manager", Set.of(OrgRole.HR_ADMIN));
        em.flush();
        em.clear();

        List<EmployeeDto> managers = hierarchyService.readAllTheManagersByOrganizationId(mine.getId());

        assertThat(managers).extracting(EmployeeDto::getEmployeeName)
                .containsExactly("My Manager");
    }

    @Test
    void managersForAnOrganization_agreeWithTheUnscopedListing() {
        Organization org = persistOrganization("Only Org");
        persistEmployee(org, "Sole Manager", Set.of(OrgRole.ORG_MANAGER));
        persistEmployee(org, "Sole Worker", Set.of());
        em.flush();
        em.clear();

        List<EmployeeDto> scoped = hierarchyService.readAllTheManagersByOrganizationId(org.getId());
        List<EmployeeDto> unscoped = hierarchyService.readAllTheManagers();

        // The two listings answer the same question and disagreed for as long as one of
        // them read the flag instead of the role.
        assertThat(scoped).extracting(EmployeeDto::getEmployeeName)
                .containsExactlyInAnyOrderElementsOf(
                        unscoped.stream()
                                .filter(e -> org.getId().equals(e.getOrganizationId()))
                                .map(EmployeeDto::getEmployeeName)
                                .toList());
        assertThat(scoped).isNotEmpty();
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        em.persist(org);
        return org;
    }

    private void persistEmployee(Organization org, String name, Set<OrgRole> roles) {
        User user = new User();
        user.setKeycloakId("kc-" + name.toLowerCase().replace(" ", "-"));
        user.setName(name);
        em.persist(user);

        Employee employee = new Employee();
        employee.setOrganization(org);
        employee.setUser(user);
        employee.setEmployeeName(name);
        employee.setGender("U");
        employee.setPhoneNumber("0000000000");
        employee.setEmailAddress(name.toLowerCase().replace(" ", ".") + "@emp.test");
        employee.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
        employee.setOrgRoles(new java.util.HashSet<>(roles));
        em.persist(employee);
    }
}
