package org.tornotron.echno_backend.employee;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.attendance.ShiftTimingRepository;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.KeycloakGroupService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.user.UserRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Salary in an employee patch arrives as whatever number type Jackson picked: a whole number
 * such as 15000 is an Integer, not a Double. The switch used to cast it with {@code (Double)},
 * which threw ClassCastException and returned a 500.
 *
 * <p>Plain Mockito, no Spring context.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmployeeSalaryPatchTest {

    @Mock private EmployeeRepository employeeRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private UserRepository userRepository;
    @Mock private KeycloakGroupService keycloakGroupService;
    @Mock private EmployeeMapper employeeMapper;
    @Mock private EmployeeHierarchyService employeeHierarchyService;
    @Mock private ShiftTimingRepository shiftTimingRepository;
    @Mock private OrganizationSecurityService orgSecurity;

    @InjectMocks private EmployeeService service;

    private final Employee employee = new Employee();

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(1L);
        employee.setSalary(1000.0);
        when(employeeRepository.findByIdAndOrganizationId(eq(5L), any())).thenReturn(Optional.of(employee));
        // Salary is a personnel-only field, so the caller holds a personnel role.
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("a whole-number salary, which Jackson reads as Integer, is applied")
    void integerSalary_isApplied() {
        service.partialUpdateAnEmployee(Map.of("salary", 15000), 5L);

        assertThat(employee.getSalary()).isEqualTo(15000.0);
    }

    @Test
    @DisplayName("a decimal salary is still applied")
    void decimalSalary_isApplied() {
        service.partialUpdateAnEmployee(Map.of("salary", 15000.5), 5L);

        assertThat(employee.getSalary()).isEqualTo(15000.5);
    }

    @Test
    @DisplayName("a null salary clears it")
    void nullSalary_clearsIt() {
        Map<String, Object> updates = new HashMap<>();
        updates.put("salary", null);

        service.partialUpdateAnEmployee(updates, 5L);

        assertThat(employee.getSalary()).isNull();
    }
}
