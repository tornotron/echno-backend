package org.tornotron.echno_backend.employee;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.tornotron.echno_backend.attendance.mapper.ShiftTimingMapperImpl;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.mapper.AttachmentMapperImpl;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.common.service.KeycloakGroupService;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.employee.dto.EmployeeJoinOrgDto;
import org.tornotron.echno_backend.employee.enums.EmployeeStatus;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapperImpl;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.User;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * A newly created employee must have a reporting manager (ClickUp 14zdkkvrf25, #823). The rule
 * lives in {@link EmployeeService}, on the one path every create goes through
 * ({@code joinOrganization}, reached by both controllers and by invite-code redemption), so
 * this test drives the service against the database rather than a mocked controller.
 *
 * <p>The exception is the first employee of an organization, who has nobody to report to. It is
 * keyed on the organization having no active employee yet, so an organization whose only
 * employees are inactive is treated the same way, and it is a create-time rule only: an
 * existing employee's manager may still be cleared or changed afterwards.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({EmployeeService.class, EmployeeHierarchyService.class, EmployeeMapperImpl.class,
        AttachmentMapperImpl.class, ShiftTimingMapperImpl.class,
        org.tornotron.echno_backend.common.service.OrganizationSecurityService.class,
        org.tornotron.echno_backend.user.UserContextService.class})
class EmployeeJoinManagerRequiredIT extends AbstractIntegrationTest {

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private TestEntityManager em;

    @MockBean
    private KeycloakGroupService keycloakGroupService;

    @MockBean
    private FileStorageService fileStorageService;

    private Long orgAId;
    private Long orgBId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        orgAId = persistOrganization("Org A").getId();
        orgBId = persistOrganization("Org B").getId();
        em.flush();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void firstEmployeeOfAnOrganization_mayJoinWithoutAManager() {
        User founder = persistUser("founder");
        em.flush();

        EmployeeDto result = employeeService.joinOrganization(founder.getId(), orgAId, joinDto());

        assertThat(result.getManagerId()).isNull();
        assertThat(employeeRepository.findById(result.getId()).orElseThrow().getManager()).isNull();
    }

    @Test
    void secondEmployeeWithoutAManager_isRefused() {
        Long founderId = joinAsFirstEmployee("founder");
        User second = persistUser("second");
        em.flush();

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> employeeService.joinOrganization(second.getId(), orgAId, joinDto()))
                .withMessageContaining("managerId is required");

        // The refusal leaves nothing behind: the organization still has exactly its founder.
        assertThat(employeeRepository.findEmployeesByOrganization_Id(orgAId))
                .extracting(Employee::getId)
                .containsExactly(founderId);
    }

    @Test
    void secondEmployeeReportingToTheFirst_joinsWithThatManager() {
        Long founderId = joinAsFirstEmployee("founder");
        User second = persistUser("second");
        em.flush();

        EmployeeJoinOrgDto dto = joinDto();
        dto.setManagerId(founderId);

        EmployeeDto result = employeeService.joinOrganization(second.getId(), orgAId, dto);

        assertThat(result.getManagerId()).isEqualTo(founderId);
        assertThat(employeeRepository.findById(result.getId()).orElseThrow().getManager().getId())
                .isEqualTo(founderId);
    }

    @Test
    void managerFromAnotherOrganization_isRefused() {
        Long orgBFounderId = joinAsFirstEmployee("org-b-founder", orgBId);
        User joiner = persistUser("joiner");
        em.flush();

        EmployeeJoinOrgDto dto = joinDto();
        dto.setManagerId(orgBFounderId);

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> employeeService.joinOrganization(joiner.getId(), orgAId, dto));
        assertThat(employeeRepository.findEmployeesByOrganization_Id(orgAId)).isEmpty();
    }

    @Test
    void anInactiveEmployee_cannotBeTheManagerOfANewEmployee() {
        Long founderId = joinAsFirstEmployee("founder");
        Long leaverId = joinAs("leaver", founderId);
        Employee leaver = employeeRepository.findById(leaverId).orElseThrow();
        leaver.setStatus(EmployeeStatus.terminated);
        employeeRepository.saveAndFlush(leaver);
        User second = persistUser("second");
        em.flush();

        EmployeeJoinOrgDto dto = joinDto();
        dto.setManagerId(leaverId);

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> employeeService.joinOrganization(second.getId(), orgAId, dto))
                .withMessageContaining("not an active employee");
    }

    @Test
    void anOrganizationWithOnlyInactiveEmployees_countsAsHavingNoneToReportTo() {
        Long founderId = joinAsFirstEmployee("founder");
        Employee founder = employeeRepository.findById(founderId).orElseThrow();
        founder.setStatus(EmployeeStatus.terminated);
        employeeRepository.saveAndFlush(founder);
        User second = persistUser("second");
        em.flush();

        EmployeeDto result = employeeService.joinOrganization(second.getId(), orgAId, joinDto());

        assertThat(result.getManagerId()).isNull();
    }

    @Test
    void anExistingEmployeesManager_mayStillBeClearedAfterwards() {
        Long founderId = joinAsFirstEmployee("founder");
        User second = persistUser("second");
        em.flush();
        EmployeeJoinOrgDto dto = joinDto();
        dto.setManagerId(founderId);
        Long secondId = employeeService.joinOrganization(second.getId(), orgAId, dto).getId();

        TenantContext.setCurrentOrgId(orgAId);
        EmployeeDto cleared = employeeService.removeManager(secondId);

        assertThat(cleared.getManagerId()).isNull();
    }

    private Long joinAsFirstEmployee(String name) {
        return joinAsFirstEmployee(name, orgAId);
    }

    private Long joinAs(String name, Long managerId) {
        User user = persistUser(name);
        em.flush();
        EmployeeJoinOrgDto dto = joinDto();
        dto.setManagerId(managerId);
        return employeeService.joinOrganization(user.getId(), orgAId, dto).getId();
    }

    private Long joinAsFirstEmployee(String name, Long orgId) {
        User user = persistUser(name);
        em.flush();
        return employeeService.joinOrganization(user.getId(), orgId, joinDto()).getId();
    }

    private EmployeeJoinOrgDto joinDto() {
        EmployeeJoinOrgDto dto = new EmployeeJoinOrgDto();
        dto.setDesignation("Site Engineer");
        dto.setDepartment("Civil");
        dto.setStatus("active");
        return dto;
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

    private User persistUser(String name) {
        User user = new User();
        user.setKeycloakId("kc-" + name);
        user.setName(name);
        user.setGender("U");
        user.setEmail(name + "@emp.test");
        user.setPhone("00000" + name.hashCode());
        user.setDateOfBirth(LocalDateTime.of(1990, 1, 1, 0, 0));
        em.persist(user);
        return user;
    }
}
