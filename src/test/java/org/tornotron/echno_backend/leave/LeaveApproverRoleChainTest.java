package org.tornotron.echno_backend.leave;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.enums.LeaveApproverRole;
import org.tornotron.echno_backend.leave.enums.LeaveStatus;
import org.tornotron.echno_backend.leave.mapper.LeaveRequestMapper;
import org.tornotron.echno_backend.organization.Organization;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How a policy's {@code approverRole} shapes the chain
 * {@link LeaveApprovalService#initializeApprovalChain} builds.
 *
 * <p>REPORTING_MANAGER is the management line as it always was, so the existing chain tests
 * cover it. What is pinned here is the other two tiers: a single level routed to an employee
 * holding the org role, never the requester, the lowest id when several hold it, and the
 * management line when nobody does.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeaveApproverRoleChainTest {

    @Mock private LeaveApprovalRepository approvalRepository;
    @Mock private LeaveRequestRepository requestRepository;
    @Mock private LeaveBalanceRepository balanceRepository;
    @Mock private LeaveTransactionRepository transactionRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private CurrentEmployeeService currentEmployeeService;
    @Mock private OrganizationSecurityService orgSecurity;
    @Mock private LeaveCalendarService calendarService;
    @Mock private NotificationService notificationService;
    @Mock private LeaveRequestMapper leaveRequestMapper;
    @Mock private LeaveRequestValidator leaveRequestValidator;

    private LeaveApprovalService service;
    private Organization org;
    private Employee manager;
    private Employee requester;

    @BeforeEach
    void setUp() {
        service = new LeaveApprovalService(approvalRepository, requestRepository, balanceRepository,
                transactionRepository, employeeRepository, currentEmployeeService, orgSecurity,
                calendarService, notificationService, leaveRequestMapper, leaveRequestValidator);
        org = new Organization();
        org.setId(1L);
        manager = employee(10L);
        requester = employee(20L);
        requester.setManager(manager);
        when(requestRepository.save(any(LeaveRequest.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Employee employee(long id) {
        Employee e = new Employee();
        e.setId(id);
        e.setOrganization(org);
        return e;
    }

    private LeaveRequest request(LeaveApproverRole role) {
        LeavePolicy policy = new LeavePolicy();
        policy.setId(5L);
        policy.setApproverRole(role);
        LeaveRequest request = new LeaveRequest();
        request.setId(99L);
        request.setEmployee(requester);
        request.setOrganization(org);
        request.setLeavePolicy(policy);
        request.setStatus(LeaveStatus.PENDING_APPROVAL);
        request.setTotalDays(1.0);
        return request;
    }

    @Test
    void hrAdminTier_routesToTheLowestIdHolderOfTheRole_asASingleLevel() {
        when(employeeRepository.findByOrganizationIdAndOrgRole(1L, OrgRole.HR_ADMIN))
                .thenReturn(List.of(employee(31L), employee(30L)));
        LeaveRequest request = request(LeaveApproverRole.HR_ADMIN);

        service.initializeApprovalChain(request);

        assertThat(request.getCurrentApprover().getId()).isEqualTo(30L);
        assertThat(request.getMaxApprovalLevel()).isEqualTo(1);
        ArgumentCaptor<LeaveApproval> saved = ArgumentCaptor.forClass(LeaveApproval.class);
        verify(approvalRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).hasSize(1);
        assertThat(saved.getValue().getApprover().getId()).isEqualTo(30L);
    }

    @Test
    void systemAdminTier_neverRoutesTheRequestToTheRequester() {
        when(employeeRepository.findByOrganizationIdAndOrgRole(1L, OrgRole.SYSTEM_ADMIN))
                .thenReturn(List.of(requester, employee(40L)));
        LeaveRequest request = request(LeaveApproverRole.SYSTEM_ADMIN);

        service.initializeApprovalChain(request);

        assertThat(request.getCurrentApprover().getId()).isEqualTo(40L);
    }

    @Test
    void aTierNobodyHolds_fallsBackToTheManagementLine() {
        when(employeeRepository.findByOrganizationIdAndOrgRole(1L, OrgRole.HR_ADMIN)).thenReturn(List.of());
        LeaveRequest request = request(LeaveApproverRole.HR_ADMIN);

        service.initializeApprovalChain(request);

        assertThat(request.getCurrentApprover().getId()).isEqualTo(manager.getId());
        assertThat(request.getStatus()).isEqualTo(LeaveStatus.PENDING_APPROVAL);
    }

    @Test
    void reportingManagerTier_doesNotConsultTheRoleTable() {
        LeaveRequest request = request(LeaveApproverRole.REPORTING_MANAGER);

        service.initializeApprovalChain(request);

        verify(employeeRepository, never()).findByOrganizationIdAndOrgRole(any(), any());
        assertThat(request.getCurrentApprover().getId()).isEqualTo(manager.getId());
    }
}
