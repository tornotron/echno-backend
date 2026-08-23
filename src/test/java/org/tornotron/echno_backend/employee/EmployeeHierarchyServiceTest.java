package org.tornotron.echno_backend.employee;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.organization.Organization;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmployeeHierarchyService}. The repository and mapper are mocked and
 * the reporting graph is built in memory. The focus is the manager-assignment validation the
 * service owns: rejecting a self-manager, rejecting a cross-organization manager, and
 * detecting a cycle anywhere up the proposed manager's chain, plus the not-found guards and
 * the null-out on manager removal.
 */
@ExtendWith(MockitoExtension.class)
class EmployeeHierarchyServiceTest {

    private static final Long ORG = 100L;

    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeMapper employeeMapper;

    private EmployeeHierarchyService service;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        service = new EmployeeHierarchyService(employeeRepository, employeeMapper);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Organization org(Long id) {
        Organization organization = new Organization();
        organization.setId(id);
        return organization;
    }

    private Employee employee(Long id, Long orgId) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setOrganization(org(orgId));
        return employee;
    }

    @Test
    void validateManager_selfManager_isRejected() {
        Employee employee = employee(5L, ORG);
        Employee manager = employee(5L, ORG);

        assertThatThrownBy(() -> service.validateManager(employee, manager))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("their own manager");
    }

    @Test
    void validateManager_differentOrganization_isRejected() {
        Employee employee = employee(5L, ORG);
        Employee manager = employee(6L, 999L);

        assertThatThrownBy(() -> service.validateManager(employee, manager))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same organization");
    }

    @Test
    void validateManager_cycleInChain_isRejected() {
        // employee(5) -> proposed manager(6) whose chain climbs back to 5.
        Employee employee = employee(5L, ORG);
        Employee top = employee(5L, ORG);
        Employee middle = employee(7L, ORG);
        middle.setManager(top);
        Employee manager = employee(6L, ORG);
        manager.setManager(middle);

        assertThatThrownBy(() -> service.validateManager(employee, manager))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("circular reference");
    }

    @Test
    void validateManager_validChain_passes() {
        Employee employee = employee(5L, ORG);
        Employee grandManager = employee(8L, ORG);
        Employee manager = employee(6L, ORG);
        manager.setManager(grandManager);

        // No exception expected for a manager whose chain never reaches the employee.
        service.validateManager(employee, manager);
    }

    @Test
    void validateManager_newEmployeeWithoutId_skipsCycleCheck() {
        Employee employee = employee(null, ORG);
        Employee manager = employee(6L, ORG);

        // A not-yet-persisted employee (null id) cannot be in any chain, so this passes.
        service.validateManager(employee, manager);
    }

    @Test
    void assignManager_persistsAndMapsWhenValid() {
        Employee employee = employee(5L, ORG);
        Employee manager = employee(6L, ORG);
        EmployeeDto dto = new EmployeeDto();
        when(employeeRepository.findByIdAndOrganizationId(5L, ORG)).thenReturn(Optional.of(employee));
        when(employeeRepository.findByIdAndOrganizationId(6L, ORG)).thenReturn(Optional.of(manager));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeMapper.toDto(any(Employee.class))).thenReturn(dto);

        EmployeeDto result = service.assignManager(5L, 6L);

        assertThat(result).isSameAs(dto);
        assertThat(employee.getManager()).isSameAs(manager);
        verify(employeeRepository).save(employee);
    }

    @Test
    void assignManager_unknownEmployee_throwsNotFound() {
        when(employeeRepository.findByIdAndOrganizationId(5L, ORG)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.assignManager(5L, 6L));
        verify(employeeRepository, never()).save(any());
    }

    @Test
    void assignManager_unknownManager_throwsNotFound() {
        Employee employee = employee(5L, ORG);
        when(employeeRepository.findByIdAndOrganizationId(5L, ORG)).thenReturn(Optional.of(employee));
        when(employeeRepository.findByIdAndOrganizationId(6L, ORG)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.assignManager(5L, 6L));
        verify(employeeRepository, never()).save(any());
    }

    @Test
    void assignManager_invalidManager_propagatesAndDoesNotPersist() {
        Employee employee = employee(5L, ORG);
        Employee manager = employee(6L, 999L); // different org
        when(employeeRepository.findByIdAndOrganizationId(5L, ORG)).thenReturn(Optional.of(employee));
        when(employeeRepository.findByIdAndOrganizationId(6L, ORG)).thenReturn(Optional.of(manager));

        assertThatThrownBy(() -> service.assignManager(5L, 6L))
                .isInstanceOf(IllegalArgumentException.class);
        verify(employeeRepository, never()).save(any());
    }

    @Test
    void removeManager_clearsManagerAndSaves() {
        Employee employee = employee(5L, ORG);
        employee.setManager(employee(6L, ORG));
        when(employeeRepository.findByIdAndOrganizationId(5L, ORG)).thenReturn(Optional.of(employee));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeMapper.toDto(any(Employee.class))).thenReturn(new EmployeeDto());

        service.removeManager(5L);

        assertThat(employee.getManager()).isNull();
        verify(employeeRepository).save(employee);
    }

    @Test
    void removeManager_unknownEmployee_throwsNotFound() {
        when(employeeRepository.findByIdAndOrganizationId(5L, ORG)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.removeManager(5L));
    }

    @Test
    void getDirectSubordinates_unknownManager_throwsNotFound() {
        when(employeeRepository.existsByIdAndOrganization_Id(6L, ORG)).thenReturn(false);

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.getDirectSubordinates(6L));
    }

    @Test
    void getDirectSubordinates_mapsEachSubordinate() {
        Employee sub1 = employee(1L, ORG);
        Employee sub2 = employee(2L, ORG);
        when(employeeRepository.existsByIdAndOrganization_Id(6L, ORG)).thenReturn(true);
        when(employeeRepository.findByManager_Id(6L)).thenReturn(List.of(sub1, sub2));
        lenient().when(employeeMapper.toDto(any(Employee.class))).thenReturn(new EmployeeDto());

        List<EmployeeDto> result = service.getDirectSubordinates(6L);

        assertThat(result).hasSize(2);
        verify(employeeMapper).toDto(sub1);
        verify(employeeMapper).toDto(sub2);
    }
}
