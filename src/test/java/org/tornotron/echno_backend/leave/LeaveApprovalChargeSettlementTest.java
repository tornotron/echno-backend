package org.tornotron.echno_backend.leave;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.enums.LeaveStatus;
import org.tornotron.echno_backend.leave.enums.WeekendHolidayTreatment;
import org.tornotron.echno_backend.leave.mapper.LeaveRequestMapper;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * A pending request is charged under the policy's treatment at the moment it is approved, and
 * the hold taken at submission is released at the figure it was taken at.
 *
 * <p>The request below was submitted as four days (Fri to Mon, counted end to end) and the
 * policy has since moved to EXCLUDE_NON_WORKING_DAYS. Approval releases the four-day hold and
 * deducts the two days the treatment now charges; nothing already approved is touched, because
 * this runs only inside the approval.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeaveApprovalChargeSettlementTest {

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
    private LeaveRequest request;
    private LeaveBalance balance;

    @BeforeEach
    void setUp() {
        service = new LeaveApprovalService(approvalRepository, requestRepository, balanceRepository,
                transactionRepository, employeeRepository, currentEmployeeService, orgSecurity,
                calendarService, notificationService, leaveRequestMapper, leaveRequestValidator);

        Organization org = new Organization();
        org.setId(1L);
        Employee applicant = new Employee();
        applicant.setId(20L);
        applicant.setOrganization(org);
        LeavePolicy policy = new LeavePolicy();
        policy.setId(5L);
        policy.setWeekendHolidayTreatment(WeekendHolidayTreatment.EXCLUDE_NON_WORKING_DAYS);

        request = new LeaveRequest();
        request.setId(99L);
        request.setEmployee(applicant);
        request.setOrganization(org);
        request.setLeavePolicy(policy);
        request.setStartDate(LocalDate.of(2026, 8, 28));
        request.setEndDate(LocalDate.of(2026, 8, 31));
        request.setTotalDays(4.0);
        request.setDeductionRule(WeekendHolidayTreatment.CHARGE_ALL_DAYS);
        request.setStatus(LeaveStatus.PENDING_APPROVAL);

        balance = new LeaveBalance();
        balance.setAccrued(12.0);
        balance.setUsed(1.0);
        balance.setPending(4.0);
        balance.setOpeningBalance(0.0);
        balance.setCarryForwardFromPrevious(0.0);

        when(requestRepository.save(any(LeaveRequest.class))).thenAnswer(i -> i.getArgument(0));
        when(balanceRepository.findByEmployeeIdAndLeavePolicyIdAndYear(anyLong(), anyLong(), anyInt()))
                .thenReturn(Optional.of(balance));
        when(leaveRequestValidator.charge(any(), any(), any(), any(), any()))
                .thenReturn(new LeaveCharge(2.0, 4.0, 2, WeekendHolidayTreatment.EXCLUDE_NON_WORKING_DAYS));
    }

    private void approveAsSoleApprover() {
        Employee manager = new Employee();
        manager.setId(30L);
        manager.setOrganization(request.getOrganization());
        request.getEmployee().setManager(manager);
        request.setMaxApprovalLevel(1);
        request.setCurrentApprovalLevel(1);
        request.setCurrentApprover(manager);
        LeaveApproval pending = new LeaveApproval();
        pending.setApprover(manager);
        pending.setApprovalLevel(1);
        when(requestRepository.lockByIdAndOrganizationId(any(), any())).thenReturn(Optional.of(request));
        when(currentEmployeeService.requireCurrentEmployee(any())).thenReturn(manager);
        when(approvalRepository.findFirstByLeaveRequestIdAndApprovalLevelAndActionOrderByCreatedAtDesc(
                any(), any(), any())).thenReturn(Optional.of(pending));
        when(leaveRequestMapper.toDto(any(LeaveRequest.class))).thenReturn(new org.tornotron.echno_backend.leave.dto.LeaveRequestDto());
        service.approve(99L, new org.tornotron.echno_backend.leave.dto.LeaveApprovalActionDto());
    }

    @Test
    void approvalOfAPendingRequest_chargesUnderTheCurrentTreatment_andReleasesTheOriginalHold() {
        approveAsSoleApprover();

        assertThat(request.getStatus()).isEqualTo(LeaveStatus.APPROVED);
        assertThat(request.getTotalDays()).isEqualTo(2.0);
        assertThat(request.getDeductionRule()).isEqualTo(WeekendHolidayTreatment.EXCLUDE_NON_WORKING_DAYS);
        assertThat(balance.getPending()).isEqualTo(0.0);
        assertThat(balance.getUsed()).isEqualTo(3.0);
    }

    @Test
    void finalizingDuringSubmission_releasesNoHold_becauseNoneWasTaken() {
        // No approver resolvable: the chain finalizes before the submitting service takes the
        // hold, so the 4.0 pending here belongs to some other request and must survive.
        service.initializeApprovalChain(request);

        assertThat(request.getStatus()).isEqualTo(LeaveStatus.APPROVED);
        assertThat(balance.getPending()).isEqualTo(4.0);
        assertThat(balance.getUsed()).isEqualTo(3.0);
    }

    @Test
    void aRequestRechargedToZero_isRefusedRatherThanApprovedAtNothing() {
        when(leaveRequestValidator.charge(any(), any(), any(), any(), any()))
                .thenReturn(new LeaveCharge(0.0, 4.0, 4, WeekendHolidayTreatment.EXCLUDE_NON_WORKING_DAYS));

        org.assertj.core.api.Assertions.assertThatThrownBy(this::approveAsSoleApprover)
                .isInstanceOf(org.tornotron.echno_backend.common.exception.InvalidRequestException.class)
                .hasMessageContaining("withdrawn");
        assertThat(request.getStatus()).isEqualTo(LeaveStatus.PENDING_APPROVAL);
    }

    @Test
    void aRequestRechargedBeyondTheBalance_isRefused() {
        // Held 4, now charged 12 under SANDWICH across a long weekend, with 11 available after the
        // hold is released: refused, and the request stays pending for the employee to amend.
        when(leaveRequestValidator.charge(any(), any(), any(), any(), any()))
                .thenReturn(new LeaveCharge(12.0, 12.0, 0, WeekendHolidayTreatment.SANDWICH));
        when(balanceRepository.lockByEmployeeIdAndLeavePolicyIdAndYear(anyLong(), anyLong(), anyInt()))
                .thenReturn(Optional.of(balance));

        org.assertj.core.api.Assertions.assertThatThrownBy(this::approveAsSoleApprover)
                .isInstanceOf(org.tornotron.echno_backend.common.exception.InvalidRequestException.class)
                .hasMessageContaining("11.0 days are available");
        assertThat(request.getTotalDays()).isEqualTo(4.0);
    }
}
