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
import org.tornotron.echno_backend.leave.dto.LeaveApprovalDto;
import org.tornotron.echno_backend.leave.enums.ApprovalAction;
import org.tornotron.echno_backend.leave.enums.LeaveStatus;
import org.tornotron.echno_backend.leave.mapper.LeaveRequestMapper;
import org.tornotron.echno_backend.organization.Organization;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Who may read a leave request's approval trail, and who the can-approve check answers about.
 *
 * <p>Both endpoints were unreachable: the trail reads asked for {@code hasAuthority('leave:read')}
 * and the check for {@code hasAuthority('leave:approve')}, neither of which this realm can issue.
 * Repairing the guard is only half the answer, because the guard an annotation can evaluate sees
 * the request id the caller sent and nothing about the record behind it. The trail is the audit of
 * a decision that moves an employee's days, so who may read it is settled here, against the record.
 *
 * <p>An approver has to be in that set. A request arriving at level two carries no account of what
 * level one said unless its approver can read the trail, so refusing them the trail refuses them
 * the decision. That is the shape #666 was: the endpoint that returned 403 was repaired and the
 * caller was still unable to complete the workflow it belonged to.
 *
 * <p>Every test here fails on the old code. The four allowed cases fail because the old
 * {@code getApprovalHistory} let anybody through and so never refused, making the refusal cases the
 * ones that carry the change; the can-approve cases fail because the old signature answered about
 * whatever employee id the caller passed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeaveApprovalTrailAccessTest {

    private static final Long ORG_ID = 100L;
    private static final Long REQUEST_ID = 42L;
    private static final Long APPLICANT_ID = 7L;
    private static final Long LINE_MANAGER_ID = 8L;
    private static final Long SENIOR_MANAGER_ID = 9L;
    private static final Long BYSTANDER_ID = 11L;

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

    @BeforeEach
    void setTenant() {
        TenantContext.setCurrentOrgId(ORG_ID);
        when(leaveRequestMapper.toApprovalDto(any(LeaveApproval.class))).thenReturn(new LeaveApprovalDto());
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
                orgSecurity,
                calendarService,
                notificationService,
                leaveRequestMapper);
    }

    private Employee employee(Long id) {
        Employee employee = new Employee();
        employee.setId(id);
        Organization organization = new Organization();
        organization.setId(ORG_ID);
        employee.setOrganization(organization);
        return employee;
    }

    /**
     * A request raised by the applicant, at level one of a two-level chain, where the senior
     * manager took over level one from the line manager by delegation.
     */
    private LeaveRequest requestPendingOnTheLineManager() {
        LeaveRequest request = new LeaveRequest();
        request.setId(REQUEST_ID);
        request.setEmployee(employee(APPLICANT_ID));
        request.setCurrentApprover(employee(LINE_MANAGER_ID));
        request.setStatus(LeaveStatus.PENDING_APPROVAL);
        return request;
    }

    private List<LeaveApproval> chain() {
        LeaveApproval levelOne = new LeaveApproval();
        levelOne.setApprovalLevel(1);
        levelOne.setApprover(employee(LINE_MANAGER_ID));
        levelOne.setAction(ApprovalAction.PENDING);

        LeaveApproval levelTwo = new LeaveApproval();
        levelTwo.setApprovalLevel(2);
        levelTwo.setApprover(employee(SENIOR_MANAGER_ID));
        levelTwo.setAction(ApprovalAction.PENDING);

        return List.of(levelOne, levelTwo);
    }

    private void theRequestExists() {
        when(requestRepository.findByIdAndOrganization_Id(REQUEST_ID, ORG_ID))
                .thenReturn(Optional.of(requestPendingOnTheLineManager()));
        when(approvalRepository.findByLeaveRequestIdOrderByApprovalLevelAsc(REQUEST_ID))
                .thenReturn(chain());
    }

    private void signedInAs(Long employeeId) {
        when(currentEmployeeService.currentEmployee()).thenReturn(Optional.of(employee(employeeId)));
    }

    private void holdingNoLeaveAdminRole() {
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "hr-admin")).thenReturn(false);
    }

    @Test
    void theApproverHoldingTheRequestMayReadItsTrail() {
        theRequestExists();
        signedInAs(LINE_MANAGER_ID);
        holdingNoLeaveAdminRole();

        assertThat(service().getApprovalHistory(REQUEST_ID)).hasSize(2);
    }

    @Test
    void anApproverFurtherUpTheChainMayReadItBeforeItReachesThem() {
        // Level two has not acted and the request is not with them yet. Reading the trail is how
        // they see what is coming and what level one said when it arrives.
        theRequestExists();
        signedInAs(SENIOR_MANAGER_ID);
        holdingNoLeaveAdminRole();

        assertThat(service().getApprovalChain(REQUEST_ID)).hasSize(2);
    }

    @Test
    void theEmployeeTheLeaveBelongsToMayReadTheTrail() {
        theRequestExists();
        signedInAs(APPLICANT_ID);
        holdingNoLeaveAdminRole();

        assertThat(service().getApprovalHistory(REQUEST_ID)).hasSize(2);
    }

    @Test
    void aLeaveAdministratorMayReadAnyTrail() {
        theRequestExists();
        signedInAs(BYSTANDER_ID);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "hr-admin")).thenReturn(true);

        assertThat(service().getApprovalChain(REQUEST_ID)).hasSize(2);
    }

    @Test
    void aColleagueWhoTakesNoPartInTheRequestIsRefusedTheTrail() {
        theRequestExists();
        signedInAs(BYSTANDER_ID);
        holdingNoLeaveAdminRole();

        assertThatThrownBy(() -> service().getApprovalHistory(REQUEST_ID))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("readable by the employee it belongs to");
    }

    @Test
    void aCallerWithNoEmployeeRecordHereIsRefusedTheTrail() {
        // The shape a bootstrap administrator has: a Keycloak account with no employee row in
        // this organization. They take no part in any request, so there is nothing to show them.
        theRequestExists();
        when(currentEmployeeService.currentEmployee()).thenReturn(Optional.empty());
        holdingNoLeaveAdminRole();

        assertThatThrownBy(() -> service().getApprovalChain(REQUEST_ID))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("no employee record");
    }

    @Test
    void anApproverMayOpenTheRequestTheyAreBeingAskedToDecide() {
        // The request read was gated on the system-admin and hr-admin roles alone, so the approver
        // could not open the screen the approve, reject and delegate buttons live on. Repairing
        // the action and leaving this is what #666 did.
        theRequestExists();
        signedInAs(LINE_MANAGER_ID);
        holdingNoLeaveAdminRole();

        assertThatCode(() -> service().requireMayReadRequest(requestPendingOnTheLineManager()))
                .doesNotThrowAnyException();
    }

    @Test
    void aColleagueWhoTakesNoPartInTheRequestMayNotOpenIt() {
        theRequestExists();
        signedInAs(BYSTANDER_ID);
        holdingNoLeaveAdminRole();

        assertThatThrownBy(() -> service().requireMayReadRequest(requestPendingOnTheLineManager()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("readable by the employee it belongs to");
    }

    @Test
    void canApproveAnswersTrueForTheApproverHoldingTheRequest() {
        theRequestExists();
        signedInAs(LINE_MANAGER_ID);

        assertThat(service().canApprove(REQUEST_ID)).isTrue();
    }

    @Test
    void canApproveAnswersFalseForAnApproverWhoseTurnHasNotCome() {
        theRequestExists();
        signedInAs(SENIOR_MANAGER_ID);

        assertThat(service().canApprove(REQUEST_ID)).isFalse();
    }

    @Test
    void canApproveAnswersAboutTheCallerRatherThanTheApproverHoldingTheRequest() {
        // The old signature took the employee to ask about, so a bystander asking about the line
        // manager was told true. It is the caller's own eligibility that a client needs, and
        // anything else is one employee probing another's place in a chain.
        theRequestExists();
        signedInAs(BYSTANDER_ID);

        assertThat(service().canApprove(REQUEST_ID)).isFalse();
    }

    @Test
    void canApproveAnswersFalseForACallerWithNoEmployeeRecordHere() {
        theRequestExists();
        when(currentEmployeeService.currentEmployee()).thenReturn(Optional.empty());

        assertThat(service().canApprove(REQUEST_ID)).isFalse();
    }
}
