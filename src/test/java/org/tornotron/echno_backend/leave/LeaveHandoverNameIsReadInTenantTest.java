package org.tornotron.echno_backend.leave;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.dto.LeaveRequestDto;
import org.tornotron.echno_backend.leave.mapper.LeaveRequestMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The handover employee's name is resolved inside the caller's organization, like every other
 * employee lookup in this service.
 *
 * <p>{@code handoverToId} is a plain scalar column and no write path checks it against the
 * tenant, so it can name an employee of another organization. The read then asked for that row
 * by primary key, and a primary-key load is the one access Hibernate's {@code orgFilter} never
 * narrows: the condition is applied to queries, and {@code findById} is not one.
 *
 * <p>What kept it from being a leak is {@link org.tornotron.echno_backend.common.multitenancy
 * .TenantIsolationLoadListener}, which denies a foreign row at the load boundary. Leaning on
 * that has a cost, because the denial is an exception rather than an empty answer: the whole
 * request-detail read fails where only one optional name could not be resolved. Asking the
 * scoped question gives back nothing, which is what an unresolvable name should look like.
 *
 * <p>Same shape as #589, #599, #607, #631, #635 and #666: a workflow reaching around the tenant
 * scope on one call while every neighbouring call observes it. See issue #741.
 *
 * <p>Against the old code both tests fail: the first because the scoped lookup is never made, the
 * second because the unscoped one answers with the other organization's employee.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeaveHandoverNameIsReadInTenantTest {

    private static final Long ORG_ID = 100L;
    private static final Long REQUEST_ID = 42L;
    private static final Long HANDOVER_TO_ID = 12L;

    @Mock private LeaveRequestRepository requestRepository;
    @Mock private LeaveRequestSequenceRepository sequenceRepository;
    @Mock private LeavePolicyRepository policyRepository;
    @Mock private LeaveBalanceRepository balanceRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private LeaveApprovalService approvalService;
    @Mock private LeaveRequestValidator leaveRequestValidator;
    @Mock private LeaveRequestMapper leaveRequestMapper;
    @Mock private OrganizationSecurityService orgSecurity;
    @Mock private CurrentEmployeeService currentEmployeeService;

    private LeaveRequestService service;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG_ID);
        service = new LeaveRequestService(
                requestRepository,
                sequenceRepository,
                policyRepository,
                balanceRepository,
                employeeRepository,
                approvalService,
                leaveRequestValidator,
                leaveRequestMapper,
                orgSecurity,
                currentEmployeeService);

        LeaveRequest request = new LeaveRequest();
        request.setId(REQUEST_ID);
        request.setHandoverToId(HANDOVER_TO_ID);
        when(requestRepository.findByIdAndOrganization_Id(REQUEST_ID, ORG_ID))
                .thenReturn(Optional.of(request));
        when(leaveRequestMapper.toDto(request)).thenReturn(new LeaveRequestDto());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void aColleagueInTheSameOrganizationIsNamed() {
        when(employeeRepository.findByIdAndOrganizationId(HANDOVER_TO_ID, ORG_ID))
                .thenReturn(Optional.of(employeeNamed("Deepak Nair")));

        LeaveRequestDto dto = service.getRequest(REQUEST_ID);

        assertThat(dto.getHandoverToName()).isEqualTo("Deepak Nair");
        verify(employeeRepository, never()).findById(anyLong());
    }

    @Test
    void anEmployeeOfAnotherOrganizationIsNotNamed() {
        // The row exists and an unscoped lookup would find it. The scoped one does not, and the
        // read carries on with the name unresolved rather than serving it or failing outright.
        when(employeeRepository.findById(HANDOVER_TO_ID))
                .thenReturn(Optional.of(employeeNamed("Someone Else")));
        when(employeeRepository.findByIdAndOrganizationId(HANDOVER_TO_ID, ORG_ID))
                .thenReturn(Optional.empty());

        LeaveRequestDto dto = service.getRequest(REQUEST_ID);

        assertThat(dto.getHandoverToName()).isNull();
    }

    private Employee employeeNamed(String name) {
        Employee employee = new Employee();
        employee.setId(HANDOVER_TO_ID);
        employee.setEmployeeName(name);
        return employee;
    }
}
