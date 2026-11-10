package org.tornotron.echno_backend.leave;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.dto.LeaveApprovalActionDto;
import org.tornotron.echno_backend.leave.dto.LeaveRequestDto;
import org.tornotron.echno_backend.leave.enums.ApprovalAction;
import org.tornotron.echno_backend.leave.enums.LeaveStatus;
import org.tornotron.echno_backend.leave.mapper.LeaveRequestMapper;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * Unit tests for single-level leave approval (ClickUp 14zdkkvzx68): a request goes to the
 * employee's immediate manager alone, one approval decides it, and that manager may transfer it
 * only to a manager above them in their own reporting line. The repositories and collaborators
 * are mocked and the entity graph is built in memory.
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
    @Mock private LeaveAccess leaveAccess;
    @Mock private LeaveCalendarService calendarService;
    @Mock private NotificationService notificationService;
    @Mock private LeaveRequestMapper leaveRequestMapper;
    @Mock private LeaveRequestValidator leaveRequestValidator;

    private LeaveApprovalService service() {
        return new LeaveApprovalService(
                approvalRepository,
                requestRepository,
                balanceRepository,
                transactionRepository,
                employeeRepository,
                currentEmployeeService,
                leaveAccess,
                calendarService,
                notificationService,
                leaveRequestMapper,
                leaveRequestValidator,
                org.mockito.Mockito.mock(org.tornotron.echno_backend.modules.access.api.AccessRoleHolders.class));
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

    private LeavePolicy policy() {
        LeavePolicy policy = new LeavePolicy();
        policy.setId(POLICY_ID);
        policy.setOrganization(organization());
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
    void aRequestGoesToTheImmediateManagerAloneHoweverLongTheLineAboveIs() {
        Employee director = employee(4L, null);
        Employee operationsHead = employee(3L, director);
        Employee lineManager = employee(2L, operationsHead);
        Employee applicant = employee(1L, lineManager);
        LeaveRequest request = request(applicant, policy());

        service().initializeApprovalChain(request);

        assertThat(request.getMaxApprovalLevel()).isEqualTo(1);
        assertThat(request.getCurrentApprovalLevel()).isEqualTo(1);
        assertThat(request.getCurrentApprover()).isEqualTo(lineManager);
        assertThat(request.getStatus()).isEqualTo(LeaveStatus.PENDING_APPROVAL);

        ArgumentCaptor<LeaveApproval> captor = ArgumentCaptor.forClass(LeaveApproval.class);
        verify(approvalRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getApprovalLevel()).isEqualTo(1);
        assertThat(captor.getValue().getApprover()).isEqualTo(lineManager);

        verify(notificationService).sendApprovalRequiredNotification(request, lineManager);
    }

    @Test
    void theImmediateManagersApprovalDecidesTheRequest() {
        Employee topManager = employee(3L, null);
        Employee lineManager = employee(2L, topManager);
        Employee applicant = employee(1L, lineManager);
        LeaveRequest request = request(applicant, policy());

        LeaveApprovalService service = service();
        service.initializeApprovalChain(request);

        approveAs(service, request, lineManager, 1);

        assertThat(request.getStatus()).isEqualTo(LeaveStatus.APPROVED);
        assertThat(request.getCurrentApprover()).isNull();
        verify(calendarService).createCalendarEntries(request);
        verify(notificationService).sendLeaveDecisionNotification(request, ApprovalAction.APPROVED);
        verify(notificationService, never()).sendApprovalRequiredNotification(request, topManager);
    }

    @Test
    void aRequestRaisedUnderTheOldChainIsDecidedByWhoeverItWaitsOnNow() {
        // Raised before single approval: three levels, waiting on the second.
        Employee topManager = employee(3L, null);
        Employee lineManager = employee(2L, topManager);
        Employee applicant = employee(1L, lineManager);
        LeaveRequest request = request(applicant, policy());
        request.setMaxApprovalLevel(3);
        request.setCurrentApprovalLevel(2);
        request.setCurrentApprover(topManager);

        approveAs(service(), request, topManager, 2);

        assertThat(request.getStatus()).isEqualTo(LeaveStatus.APPROVED);
        verify(notificationService).sendLeaveDecisionNotification(request, ApprovalAction.APPROVED);
    }

    @Test
    void noResolvableApprovers_finalizesImmediately() {
        Employee applicant = employee(1L, null);
        LeaveRequest request = request(applicant, policy());
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
    void theManagerCanTransferTheRequestToAnyoneAboveThemInTheirLine() {
        Employee director = employee(4L, null);
        Employee operationsHead = employee(3L, director);
        Employee lineManager = employee(2L, operationsHead);
        Employee applicant = employee(1L, lineManager);
        LeaveRequest request = waitingOn(applicant, lineManager);

        transferAs(lineManager, request, director);

        assertThat(request.getCurrentApprover()).isEqualTo(director);
        verify(notificationService).sendDelegationNotification(request, director, lineManager.getId());
    }

    @Test
    void aTransferToSomebodyOutsideTheLineAboveIsRefused() {
        Employee operationsHead = employee(3L, null);
        Employee lineManager = employee(2L, operationsHead);
        Employee peer = employee(5L, operationsHead);
        Employee applicant = employee(1L, lineManager);
        LeaveRequest request = waitingOn(applicant, lineManager);

        assertThatThrownBy(() -> transferAs(lineManager, request, peer))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("above you in your reporting line");
        assertThatThrownBy(() -> transferAs(lineManager, request, applicant))
                .isInstanceOf(InvalidRequestException.class);

        assertThat(request.getCurrentApprover()).isEqualTo(lineManager);
        verify(approvalRepository, never()).save(any());
    }

    private LeaveRequest waitingOn(Employee applicant, Employee approver) {
        LeaveRequest request = request(applicant, policy());
        request.setMaxApprovalLevel(1);
        request.setCurrentApprovalLevel(1);
        request.setCurrentApprover(approver);
        return request;
    }

    private void transferAs(Employee approver, LeaveRequest request, Employee target) {
        LeaveApproval pending = new LeaveApproval();
        pending.setLeaveRequest(request);
        pending.setApprover(approver);
        pending.setApprovalLevel(1);
        pending.setAction(ApprovalAction.PENDING);

        lenient().when(requestRepository.findByIdAndOrganization_Id(eq(REQUEST_ID), any()))
                .thenReturn(Optional.of(request));
        lenient().when(employeeRepository.findByIdAndOrganizationId(eq(target.getId()), any()))
                .thenReturn(Optional.of(target));
        lenient().when(approvalRepository.findFirstByLeaveRequestIdAndApprovalLevelAndActionOrderByCreatedAtDesc(
                eq(REQUEST_ID), eq(1), eq(ApprovalAction.PENDING)))
                .thenReturn(Optional.of(pending));
        lenient().when(currentEmployeeService.requireCurrentEmployee(anyString())).thenReturn(approver);

        LeaveApprovalActionDto dto = new LeaveApprovalActionDto();
        dto.setDelegateToId(target.getId());
        service().delegate(REQUEST_ID, dto);
    }

    private void approveAs(LeaveApprovalService service, LeaveRequest request, Employee approver, int level) {
        LeaveApproval pending = new LeaveApproval();
        pending.setLeaveRequest(request);
        pending.setApprover(approver);
        pending.setApprovalLevel(level);
        pending.setAction(ApprovalAction.PENDING);

        when(requestRepository.lockByIdAndOrganizationId(eq(REQUEST_ID), any()))
                .thenReturn(Optional.of(request));
        when(approvalRepository.findFirstByLeaveRequestIdAndApprovalLevelAndActionOrderByCreatedAtDesc(
                eq(REQUEST_ID), eq(level), eq(ApprovalAction.PENDING)))
                .thenReturn(Optional.of(pending));
        lenient().when(balanceRepository.findByEmployeeIdAndLeavePolicyIdAndYear(anyLong(), anyLong(), anyInt()))
                .thenReturn(Optional.empty());
        when(leaveRequestMapper.toDto(request)).thenReturn(new LeaveRequestDto());
        when(currentEmployeeService.requireCurrentEmployee(anyString())).thenReturn(approver);

        service.approve(REQUEST_ID, new LeaveApprovalActionDto());
    }
}
