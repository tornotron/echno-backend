package org.tornotron.echno_backend.leave;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.enums.LeaveStatus;
import org.tornotron.echno_backend.leave.mapper.LeaveRequestMapper;
import org.tornotron.echno_backend.organization.Organization;

import java.util.List;
import java.util.Optional;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An approver's queue is their own, and is read from the session rather than from the call.
 *
 * <p>{@code /leave-requests/pending-approvals} and its count took the approver as a query
 * parameter, under a guard that asked only whether the caller held system-admin or hr-admin. The
 * two never met: the guard checked a role and the query read a number the caller chose. So an
 * administrator listed any colleague's queue by asking for it, and the people who actually hold
 * these decisions could not list their own, because an approval chain is built by walking the
 * employee's management line and a line manager holds neither role. That is the same defect shape
 * closed in #589, #599, #607, #631 and #635, one endpoint further along the same workflow.
 *
 * <p>Against the old code these tests fail by serving the id the caller passed. The refusals fail
 * by the listing being served to a caller who has no employee record and therefore no queue.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeaveApproverQueueTest {

    private static final Long ORG_ID = 100L;
    private static final Long CALLER_EMPLOYEE_ID = 8L;

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

    @BeforeEach
    void setTenant() {
        TenantContext.setCurrentOrgId(ORG_ID);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private LeaveRequestService service() {
        return new LeaveRequestService(
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
    }

    private void signedInAsTheLineManager() {
        Employee caller = new Employee();
        caller.setId(CALLER_EMPLOYEE_ID);
        Organization organization = new Organization();
        organization.setId(ORG_ID);
        caller.setOrganization(organization);
        when(currentEmployeeService.requireCurrentEmployee(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(caller);
    }

    private void theCallerHasNoEmployeeRecordHere() {
        when(currentEmployeeService.requireCurrentEmployee(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new AccessDeniedException(
                        "You have no employee record in this organization"));
    }

    @Test
    void thePendingQueueIsReadForTheCallerNotForAnIdTheySend() {
        signedInAsTheLineManager();
        when(requestRepository.findByCurrentApproverIdAndStatus(CALLER_EMPLOYEE_ID, LeaveStatus.PENDING_APPROVAL))
                .thenReturn(List.of());

        assertThat(service().getPendingApprovals()).isEmpty();

        verify(requestRepository)
                .findByCurrentApproverIdAndStatus(CALLER_EMPLOYEE_ID, LeaveStatus.PENDING_APPROVAL);
    }

    @Test
    void thePendingCountIsCountedForTheCaller() {
        signedInAsTheLineManager();
        when(requestRepository.countByCurrentApproverIdAndStatus(CALLER_EMPLOYEE_ID, LeaveStatus.PENDING_APPROVAL))
                .thenReturn(3L);

        assertThat(service().getPendingApprovalCount()).isEqualTo(3L);

        verify(requestRepository)
                .countByCurrentApproverIdAndStatus(CALLER_EMPLOYEE_ID, LeaveStatus.PENDING_APPROVAL);
    }

    @Test
    void theApproverHistoryIsReadForTheCaller() {
        signedInAsTheLineManager();
        when(requestRepository.findDistinctByApproverParticipation(CALLER_EMPLOYEE_ID))
                .thenReturn(List.of());

        assertThat(service().getRequestsByApprover()).isEmpty();

        verify(requestRepository).findDistinctByApproverParticipation(CALLER_EMPLOYEE_ID);
    }

    @Test
    void readingOneRequestIsSettledAgainstTheRecordRatherThanTheRole() {
        // The request read was gated on the system-admin and hr-admin roles alone, so an approver
        // could not open the screen the approve, reject and delegate buttons live on. The rule is
        // the approval trail's, and it belongs where the record is.
        LeaveRequest request = new LeaveRequest();
        request.setId(42L);
        when(requestRepository.findByIdAndOrganization_Id(42L, ORG_ID)).thenReturn(Optional.of(request));

        service().getRequest(42L);

        verify(approvalService).requireMayReadRequest(request);
    }

    @Test
    void aRefusalFromThatCheckStopsTheRead() {
        LeaveRequest request = new LeaveRequest();
        request.setId(42L);
        when(requestRepository.findByIdAndOrganization_Id(42L, ORG_ID)).thenReturn(Optional.of(request));
        org.mockito.Mockito.doThrow(new AccessDeniedException("not your request"))
                .when(approvalService).requireMayReadRequest(request);

        assertThatThrownBy(() -> service().getRequest(42L))
                .isInstanceOf(AccessDeniedException.class);

        verify(leaveRequestMapper, never()).toDto(request);
    }

    @Test
    void aCallerWithNoEmployeeRecordHereIsRefusedAQueueRatherThanServedSomebodyElses() {
        theCallerHasNoEmployeeRecordHere();

        assertThatThrownBy(() -> service().getPendingApprovals())
                .isInstanceOf(AccessDeniedException.class);

        verify(requestRepository, never())
                .findByCurrentApproverIdAndStatus(anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aCallerWithNoEmployeeRecordHereIsRefusedTheCount() {
        theCallerHasNoEmployeeRecordHere();

        assertThatThrownBy(() -> service().getPendingApprovalCount())
                .isInstanceOf(AccessDeniedException.class);

        verify(requestRepository, never())
                .countByCurrentApproverIdAndStatus(anyLong(), org.mockito.ArgumentMatchers.any());
    }
}
