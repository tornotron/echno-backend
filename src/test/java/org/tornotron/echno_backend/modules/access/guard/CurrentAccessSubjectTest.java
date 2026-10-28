package org.tornotron.echno_backend.modules.access.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * The current subject is the signed-in user's membership in the request's organization, resolved
 * once per request, and absent whenever any part of it is missing.
 */
class CurrentAccessSubjectTest {

    private static final long ORG = 7L;
    private static final long USER = 11L;
    private static final long EMPLOYEE = 1L;

    private final UserContextService userContext = mock(UserContextService.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final CurrentAccessSubject current = new CurrentAccessSubject(userContext, employees);

    @AfterEach
    void clear() {
        TenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void isTheUsersMembershipInTheRequestsOrganization() {
        TenantContext.setCurrentOrgId(ORG);
        when(userContext.getCurrentUserId()).thenReturn(USER);
        when(employees.findByUserIdAndOrganizationId(USER, ORG)).thenReturn(Optional.of(employee()));

        assertThat(current.resolve()).contains(new AccessSubject(ORG, EMPLOYEE, USER));
    }

    @Test
    void isAbsentWithoutAnOrganizationAUserOrAMembership() {
        assertThat(current.resolve()).isEmpty();

        TenantContext.setCurrentOrgId(ORG);
        assertThat(current.resolve()).isEmpty();

        when(userContext.getCurrentUserId()).thenReturn(USER);
        when(employees.findByUserIdAndOrganizationId(USER, ORG)).thenReturn(Optional.empty());
        assertThat(current.resolve()).isEmpty();
    }

    @Test
    void isResolvedOncePerRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        TenantContext.setCurrentOrgId(ORG);
        when(userContext.getCurrentUserId()).thenReturn(USER);
        when(employees.findByUserIdAndOrganizationId(USER, ORG)).thenReturn(Optional.of(employee()));

        current.resolve();
        current.resolve();

        verify(employees, times(1)).findByUserIdAndOrganizationId(USER, ORG);
    }

    private static Employee employee() {
        Employee employee = new Employee();
        employee.setId(EMPLOYEE);
        return employee;
    }
}
