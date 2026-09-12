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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Who may read an employee's leave balances, decided against the stored employee.
 *
 * <p>The management line is resolved by the same method that routes a leave request for approval,
 * so the readers and the approvers cannot drift apart: whoever a request would reach can read the
 * balance it draws on. The delegate branch is separate because a delegate is named on the approval
 * row and in no management line.
 *
 * <p>The three grant cases (line manager, senior manager, delegate) fail on the old guard, which
 * answered self-or-role and nothing else. The refusals are what keep this from being a widening to
 * everybody: a bystander in the same organization, and a caller with no employee record here, are
 * still refused with membership held.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeaveSecurityServiceTest {

    private static final Long ORG_ID = 100L;
    private static final Long APPLICANT_ID = 7L;
    private static final Long LINE_MANAGER_ID = 8L;
    private static final Long SENIOR_MANAGER_ID = 9L;
    private static final Long DELEGATE_ID = 10L;
    private static final Long BYSTANDER_ID = 11L;

    @Mock private OrganizationSecurityService orgSecurity;
    @Mock private CurrentEmployeeService currentEmployeeService;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private LeaveApprovalRepository approvalRepository;
    @Mock private LeaveApprovalService approvalService;

    private Employee applicant;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG_ID);
        applicant = employee(APPLICANT_ID);
        when(orgSecurity.isSelfOrHasAnyOrgRole(anyLong(), eq("system-admin"), eq("hr-admin"))).thenReturn(false);
        when(employeeRepository.findByIdAndOrganizationId(APPLICANT_ID, ORG_ID)).thenReturn(Optional.of(applicant));
        when(approvalService.resolveApprovalChain(applicant))
                .thenReturn(List.of(employee(LINE_MANAGER_ID), employee(SENIOR_MANAGER_ID)));
        when(approvalRepository.existsPendingApprovalForEmployeeByApprover(anyLong(), anyLong())).thenReturn(false);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private LeaveSecurityService service() {
        return new LeaveSecurityService(orgSecurity, currentEmployeeService, employeeRepository,
                approvalRepository, approvalService);
    }

    private static Employee employee(Long id) {
        Employee e = new Employee();
        e.setId(id);
        return e;
    }

    private void callerIs(Long employeeId) {
        when(currentEmployeeService.currentEmployee()).thenReturn(Optional.of(employee(employeeId)));
    }

    @Test
    void theLineManagerWhoApprovesTheRequestCanReadTheBalanceItDrawsOn() {
        callerIs(LINE_MANAGER_ID);

        assertThat(service().canViewEmployeeBalances(APPLICANT_ID)).isTrue();
    }

    @Test
    void aSeniorManagerFurtherUpTheChainCanReadItToo() {
        callerIs(SENIOR_MANAGER_ID);

        assertThat(service().canViewEmployeeBalances(APPLICANT_ID)).isTrue();
    }

    @Test
    void aDelegateHoldingAPendingRequestCanReadItWhileTheyHoldIt() {
        callerIs(DELEGATE_ID);
        when(approvalRepository.existsPendingApprovalForEmployeeByApprover(APPLICANT_ID, DELEGATE_ID)).thenReturn(true);

        assertThat(service().canViewEmployeeBalances(APPLICANT_ID)).isTrue();
    }

    @Test
    void aBystanderInTheSameOrganizationIsRefused() {
        callerIs(BYSTANDER_ID);

        assertThat(service().canViewEmployeeBalances(APPLICANT_ID)).isFalse();
    }

    @Test
    void aCallerWithNoEmployeeRecordHereIsRefused() {
        when(currentEmployeeService.currentEmployee()).thenReturn(Optional.empty());

        assertThat(service().canViewEmployeeBalances(APPLICANT_ID)).isFalse();
        verifyNoInteractions(approvalService);
    }

    @Test
    void anEmployeeOutsideTheTenantIsRefusedRatherThanLookedUp() {
        callerIs(LINE_MANAGER_ID);
        when(employeeRepository.findByIdAndOrganizationId(APPLICANT_ID, ORG_ID)).thenReturn(Optional.empty());

        assertThat(service().canViewEmployeeBalances(APPLICANT_ID)).isFalse();
        verifyNoInteractions(approvalService);
    }

    @Test
    void selfAndTheAdministratorRolesAnswerBeforeAnyLookup() {
        when(orgSecurity.isSelfOrHasAnyOrgRole(eq(APPLICANT_ID), eq("system-admin"), eq("hr-admin"))).thenReturn(true);

        assertThat(service().canViewEmployeeBalances(APPLICANT_ID)).isTrue();
        verifyNoInteractions(employeeRepository, approvalService, approvalRepository);
    }

    @Test
    void noTenantInForceRefuses() {
        callerIs(LINE_MANAGER_ID);
        TenantContext.clear();

        assertThat(service().canViewEmployeeBalances(APPLICANT_ID)).isFalse();
    }
}
