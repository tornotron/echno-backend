package org.tornotron.echno_backend.leave;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.dto.LeaveApprovalActionDto;
import org.tornotron.echno_backend.leave.dto.LeaveRequestDto;
import org.tornotron.echno_backend.leave.enums.ApprovalAction;
import org.tornotron.echno_backend.leave.enums.LeaveStatus;
import org.tornotron.echno_backend.leave.mapper.LeaveRequestMapper;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LeaveApprovalService#initializeApprovalChain}, covering the opt-in
 * multi-level approval toggle on the leave policy (#249). The repositories and collaborators are
 * mocked and the entity graph is built in memory.
 *
 * <p>Three shapes are exercised: a policy with the toggle enabled (the default) builds the full
 * management-line chain as before; a policy with the toggle disabled builds a single-level chain of
 * just the direct approver, and one approval then finalizes the request; an employee with no
 * resolvable approvers finalizes immediately regardless of the toggle.
 */
@ExtendWith(MockitoExtension.class)
class LeaveApprovalServiceTest {

    private static final Long ORG_ID = 100L;
    private static final Long REQUEST_ID = 42L;
    private static final Long POLICY_ID = 3L;

    @Mock private LeaveApprovalRepository approvalRepository;
    @Mock private LeaveRequestRepository requestRepository;
    @Mock private LeaveBalanceRepository balanceRepository;
    @Mock private LeaveTransactionRepository transactionRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private CurrentEmployeeService currentEmployeeService;
    @Mock private LeaveCalendarService calendarService;
    @Mock private NotificationService notificationService;
    @Mock private LeaveRequestMapper leaveRequestMapper;

    private LeaveApprovalService service() {
        return new LeaveApprovalService(
                approvalRepository,
                requestRepository,
                balanceRepository,
                transactionRepository,
                employeeRepository,
                currentEmployeeService,
                calendarService,
                notificationService,
                leaveRequestMapper);
    }

    private Organization organization() {
        Organization org = new Organization();
        org.setId(ORG_ID);
        return org;
    }

    private Employee employee(Long id, Employee manager) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setOrganization(organization());
        employee.setManager(manager);
        return employee;
    }

    private LeavePolicy policy(Boolean multiLevelEnabled) {
        LeavePolicy policy = new LeavePolicy();
        policy.setId(POLICY_ID);
        policy.setOrganization(organization());
        policy.setMultiLevelApprovalEnabled(multiLevelEnabled);
        return policy;
    }

    private LeaveRequest request(Employee applicant, LeavePolicy policy) {
        LeaveRequest request = new LeaveRequest();
        request.setId(REQUEST_ID);
        request.setOrganization(organization());
        request.setEmployee(applicant);
        request.setLeavePolicy(policy);
        request.setStartDate(LocalDate.of(2026, 6, 1));
        request.setStatus(LeaveStatus.PENDING_APPROVAL);
        return request;
    }

    @Test
    void multiLevelEnabled_buildsFullManagementLineChain() {
        Employee topManager = employee(3L, null);
        Employee lineManager = employee(2L, topManager);
        Employee applicant = employee(1L, lineManager);
        LeaveRequest request = request(applicant, policy(true));

        service().initializeApprovalChain(request);

        // Full chain: both managers in the management line become approvers.
        assertThat(request.getMaxApprovalLevel()).isEqualTo(2);
        assertThat(request.getCurrentApprovalLevel()).isEqualTo(1);
        assertThat(request.getCurrentApprover()).isEqualTo(lineManager);
        assertThat(request.getStatus()).isEqualTo(LeaveStatus.PENDING_APPROVAL);

        ArgumentCaptor<LeaveApproval> captor = ArgumentCaptor.forClass(LeaveApproval.class);
        verify(approvalRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(LeaveApproval::getApprovalLevel)
                .containsExactly(1, 2);
        assertThat(captor.getAllValues())
                .extracting(LeaveApproval::getApprover)
                .containsExactly(lineManager, topManager);

        verify(notificationService).sendApprovalRequiredNotification(request, lineManager);
    }

    @Test
    void multiLevelDisabled_buildsSingleLevelChainOfDirectApprover() {
        Employee topManager = employee(3L, null);
        Employee lineManager = employee(2L, topManager);
        Employee applicant = employee(1L, lineManager);
        LeaveRequest request = request(applicant, policy(false));

        service().initializeApprovalChain(request);

        // Single-level: only the direct approver, even though a management line exists.
        assertThat(request.getMaxApprovalLevel()).isEqualTo(1);
        assertThat(request.getCurrentApprovalLevel()).isEqualTo(1);
        assertThat(request.getCurrentApprover()).isEqualTo(lineManager);

        ArgumentCaptor<LeaveApproval> captor = ArgumentCaptor.forClass(LeaveApproval.class);
        verify(approvalRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getApprovalLevel()).isEqualTo(1);
        assertThat(captor.getValue().getApprover()).isEqualTo(lineManager);

        verify(notificationService).sendApprovalRequiredNotification(request, lineManager);
    }

    @Test
    void multiLevelDisabled_singleApprovalFinalizesRequest() {
        Employee topManager = employee(3L, null);
        Employee lineManager = employee(2L, topManager);
        Employee applicant = employee(1L, lineManager);
        LeaveRequest request = request(applicant, policy(false));

        LeaveApprovalService service = service();
        service.initializeApprovalChain(request);

        // The single-level chain is now in place; the direct approver approves.
        LeaveApproval pending = new LeaveApproval();
        pending.setLeaveRequest(request);
        pending.setApprover(lineManager);
        pending.setApprovalLevel(1);
        pending.setAction(ApprovalAction.PENDING);

        when(requestRepository.lockByIdAndOrganizationId(eq(REQUEST_ID), any()))
                .thenReturn(Optional.of(request));
        when(approvalRepository.findFirstByLeaveRequestIdAndApprovalLevelAndActionOrderByCreatedAtDesc(
                eq(REQUEST_ID), eq(1), eq(ApprovalAction.PENDING)))
                .thenReturn(Optional.of(pending));
        lenient().when(balanceRepository.findByEmployeeIdAndLeavePolicyIdAndYear(anyLong(), anyLong(), anyInt()))
                .thenReturn(Optional.empty());
        when(leaveRequestMapper.toDto(request)).thenReturn(new LeaveRequestDto());

        when(currentEmployeeService.requireCurrentEmployee(anyString())).thenReturn(lineManager);

        service.approve(REQUEST_ID, new LeaveApprovalActionDto());

        // maxApprovalLevel == 1, so the first approval finalizes rather than advancing.
        assertThat(request.getStatus()).isEqualTo(LeaveStatus.APPROVED);
        assertThat(request.getCurrentApprover()).isNull();
        verify(calendarService).createCalendarEntries(request);
        verify(notificationService).sendLeaveDecisionNotification(request, ApprovalAction.APPROVED);
    }

    @Test
    void noResolvableApprovers_finalizesImmediately() {
        Employee applicant = employee(1L, null);
        LeaveRequest request = request(applicant, policy(true));
        lenient().when(balanceRepository.findByEmployeeIdAndLeavePolicyIdAndYear(anyLong(), anyLong(), anyInt()))
                .thenReturn(Optional.empty());

        service().initializeApprovalChain(request);

        assertThat(request.getStatus()).isEqualTo(LeaveStatus.APPROVED);
        assertThat(request.getCurrentApprover()).isNull();
        verify(approvalRepository, never()).save(any());
        verify(notificationService, never()).sendApprovalRequiredNotification(any(), any());
        verify(notificationService).sendLeaveDecisionNotification(request, ApprovalAction.APPROVED);
    }

    @Test
    void nullPolicyToggle_defaultsToMultiLevel() {
        Employee topManager = employee(3L, null);
        Employee lineManager = employee(2L, topManager);
        Employee applicant = employee(1L, lineManager);
        // A legacy policy with no explicit toggle value must preserve the full-chain behaviour.
        LeaveRequest request = request(applicant, policy(null));

        service().initializeApprovalChain(request);

        assertThat(request.getMaxApprovalLevel()).isEqualTo(2);
        verify(approvalRepository, times(2)).save(any());
    }
}
