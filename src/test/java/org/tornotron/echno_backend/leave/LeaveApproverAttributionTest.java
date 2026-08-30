package org.tornotron.echno_backend.leave;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.employee.Employee;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who a leave decision is recorded as having been made by.
 *
 * <p>Approve, reject and delegate took an {@code approverId} on the payload and compared it with
 * the request's current approver. That comparison establishes that the id names the right person
 * and nothing at all about who sent it. The endpoints are gated on the system-admin and hr-admin
 * roles, so any holder of either could pass the current approver's id and have the decision
 * written under that approver's name, on the record that moves an employee's leave balance.
 *
 * <p>The acting approver now comes from the session. The payload a deployed client sends is
 * replayed verbatim, {@code approverId} and all, so these pin that the key is accepted rather
 * than refused and that it no longer decides anything. On the old code
 * {@link #anAdminWhoIsNotTheApproverCanNoLongerActInTheApproversName} passes the check and
 * approves the request.
 */
@ExtendWith(MockitoExtension.class)
class LeaveApproverAttributionTest {

    private static final Long ORG_ID = 100L;
    private static final Long REQUEST_ID = 42L;
    private static final Long APPROVER_EMPLOYEE_ID = 2L;
    private static final Long ADMIN_EMPLOYEE_ID = 8L;

    /** Exactly what the web client's approval action puts on the wire today. */
    private static final String DEPLOYED_CLIENT_PAYLOAD =
            "{\"approverId\":2,\"comments\":\"Approved, please plan handover before you leave\"}";

    @Mock private LeaveApprovalRepository approvalRepository;
    @Mock private LeaveRequestRepository requestRepository;
    @Mock private LeaveBalanceRepository balanceRepository;
    @Mock private LeaveTransactionRepository transactionRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private CurrentEmployeeService currentEmployeeService;
    @Mock private LeaveCalendarService calendarService;
    @Mock private NotificationService notificationService;
    @Mock private LeaveRequestMapper leaveRequestMapper;

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    private Employee approver;
    private LeaveRequest request;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG_ID);

        Organization organization = new Organization();
        organization.setId(ORG_ID);

        approver = new Employee();
        approver.setId(APPROVER_EMPLOYEE_ID);
        approver.setOrganization(organization);

        Employee applicant = new Employee();
        applicant.setId(1L);
        applicant.setOrganization(organization);

        LeavePolicy policy = new LeavePolicy();
        policy.setId(3L);
        policy.setOrganization(organization);

        request = new LeaveRequest();
        request.setId(REQUEST_ID);
        request.setOrganization(organization);
        request.setEmployee(applicant);
        request.setLeavePolicy(policy);
        request.setStartDate(LocalDate.of(2026, 6, 1));
        request.setStatus(LeaveStatus.PENDING_APPROVAL);
        request.setCurrentApprovalLevel(1);
        request.setMaxApprovalLevel(1);
        request.setCurrentApprover(approver);

        LeaveApproval pending = new LeaveApproval();
        pending.setLeaveRequest(request);
        pending.setOrganization(organization);
        pending.setApprover(approver);
        pending.setApprovalLevel(1);
        pending.setAction(ApprovalAction.PENDING);

        lenient().when(requestRepository.lockByIdAndOrganizationId(eq(REQUEST_ID), any()))
                .thenReturn(Optional.of(request));
        lenient().when(approvalRepository.findFirstByLeaveRequestIdAndApprovalLevelAndActionOrderByCreatedAtDesc(
                        eq(REQUEST_ID), eq(1), eq(ApprovalAction.PENDING)))
                .thenReturn(Optional.of(pending));
        lenient().when(balanceRepository.findByEmployeeIdAndLeavePolicyIdAndYear(anyLong(), anyLong(), anyInt()))
                .thenReturn(Optional.empty());
        lenient().when(leaveRequestMapper.toDto(request)).thenReturn(new LeaveRequestDto());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

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

    private LeaveApprovalActionDto deployedClientPayload() throws Exception {
        return objectMapper.readValue(DEPLOYED_CLIENT_PAYLOAD, LeaveApprovalActionDto.class);
    }

    private Employee admin() {
        Employee admin = new Employee();
        admin.setId(ADMIN_EMPLOYEE_ID);
        return admin;
    }

    @Test
    void anApproverIdFromAnOlderClientIsAcceptedRatherThanRefused() throws Exception {
        LeaveApprovalActionDto dto = deployedClientPayload();

        assertThat(dto.getComments()).startsWith("Approved, please plan handover");
    }

    @Test
    void theCurrentApproverActingOnTheirOwnQueueIsStillServed() throws Exception {
        when(currentEmployeeService.requireCurrentEmployee(anyString())).thenReturn(approver);

        service().approve(REQUEST_ID, deployedClientPayload());

        assertThat(request.getStatus()).isEqualTo(LeaveStatus.APPROVED);
        ArgumentCaptor<LeaveApproval> saved = ArgumentCaptor.forClass(LeaveApproval.class);
        verify(approvalRepository).save(saved.capture());
        assertThat(saved.getValue().getApprover()).isEqualTo(approver);
        assertThat(saved.getValue().getAction()).isEqualTo(ApprovalAction.APPROVED);
    }

    /**
     * The case the payload field bought, and the one it should never have: the payload names the
     * real approver and the session is somebody else who holds the role gate.
     */
    @Test
    void anAdminWhoIsNotTheApproverCanNoLongerActInTheApproversName() throws Exception {
        when(currentEmployeeService.requireCurrentEmployee(anyString())).thenReturn(admin());

        LeaveApprovalActionDto dto = deployedClientPayload();
        assertThatThrownBy(() -> service().approve(REQUEST_ID, dto))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("not the current approver");

        assertThat(request.getStatus()).isEqualTo(LeaveStatus.PENDING_APPROVAL);
        verify(approvalRepository, never()).save(any());
    }

    @Test
    void rejectIsSettledOnTheSessionToo() throws Exception {
        when(currentEmployeeService.requireCurrentEmployee(anyString())).thenReturn(admin());

        LeaveApprovalActionDto dto = deployedClientPayload();
        assertThatThrownBy(() -> service().reject(REQUEST_ID, dto))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("not the current approver");

        assertThat(request.getStatus()).isEqualTo(LeaveStatus.PENDING_APPROVAL);
        verify(approvalRepository, never()).save(any());
    }

    @Test
    void delegateRecordsWhoDelegatedFromTheSession() throws Exception {
        when(currentEmployeeService.requireCurrentEmployee(anyString())).thenReturn(approver);
        when(requestRepository.findByIdAndOrganization_Id(eq(REQUEST_ID), any()))
                .thenReturn(Optional.of(request));
        Employee delegate = new Employee();
        delegate.setId(9L);
        when(employeeRepository.findByIdAndOrganizationId(eq(9L), any()))
                .thenReturn(Optional.of(delegate));

        LeaveApprovalActionDto dto = deployedClientPayload();
        dto.setDelegateToId(9L);
        service().delegate(REQUEST_ID, dto);

        ArgumentCaptor<LeaveApproval> saved = ArgumentCaptor.forClass(LeaveApproval.class);
        verify(approvalRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(1).getDelegatedFromId()).isEqualTo(APPROVER_EMPLOYEE_ID);
        verify(notificationService).sendDelegationNotification(request, delegate, APPROVER_EMPLOYEE_ID);
    }

    @Test
    void aCallerWithNoEmployeeRecordCannotDecideAnything() throws Exception {
        when(currentEmployeeService.requireCurrentEmployee(anyString()))
                .thenThrow(new AccessDeniedException("no employee record"));

        LeaveApprovalActionDto dto = deployedClientPayload();
        assertThatThrownBy(() -> service().approve(REQUEST_ID, dto))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(request.getStatus()).isEqualTo(LeaveStatus.PENDING_APPROVAL);
        verify(approvalRepository, never()).save(any());
    }
}
