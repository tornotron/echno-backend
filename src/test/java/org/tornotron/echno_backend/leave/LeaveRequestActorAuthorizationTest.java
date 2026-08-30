package org.tornotron.echno_backend.leave;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.dto.LeaveRequestCreationDto;
import org.tornotron.echno_backend.leave.enums.LeaveStatus;
import org.tornotron.echno_backend.leave.mapper.LeaveRequestMapper;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Who may raise leave, and who may act on a leave request once it exists.
 *
 * <p>Both questions used to be answered by an id the caller sent. Creation was guarded by tenant
 * membership alone while taking the employee as a query parameter, so any colleague could file
 * leave in somebody else's name: the request carried their employee record, held days against
 * their balance and entered their approval chain. The request-scoped calls were guarded by a
 * self-check on that same parameter, which is not tied to the request being acted on, so passing
 * your own employee id alongside a colleague's request id got you through the guard and then let
 * the service edit, submit, cancel or withdraw their request.
 *
 * <p>What settles it now is the record: the employee is read off the stored request rather than
 * off the call, and only that employee or a leave admin gets through. These tests fail on the
 * old code, the create case with a saved request rather than a refusal and the others by the
 * cancellation going through.
 */
@ExtendWith(MockitoExtension.class)
class LeaveRequestActorAuthorizationTest {

    private static final Long ORG_ID = 100L;
    private static final Long CALLER_EMPLOYEE_ID = 7L;
    private static final Long COLLEAGUE_EMPLOYEE_ID = 8L;
    private static final Long REQUEST_ID = 42L;

    @Mock private LeaveRequestRepository requestRepository;
    @Mock private LeaveRequestSequenceRepository sequenceRepository;
    @Mock private LeavePolicyRepository policyRepository;
    @Mock private LeaveBalanceRepository balanceRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private LeaveApprovalService approvalService;
    @Mock private LeaveRequestValidator leaveRequestValidator;
    @Mock private LeaveRequestMapper leaveRequestMapper;
    @Mock private OrganizationSecurityService orgSecurity;

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
                orgSecurity);
    }

    private Organization organization() {
        Organization organization = new Organization();
        organization.setId(ORG_ID);
        return organization;
    }

    private Employee employee(Long id) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setOrganization(organization());
        return employee;
    }

    /** A colleague's request, approved, so cancelling it would release days from their balance. */
    private LeaveRequest colleaguesApprovedRequest() {
        LeaveRequest request = new LeaveRequest();
        request.setId(REQUEST_ID);
        request.setEmployee(employee(COLLEAGUE_EMPLOYEE_ID));
        request.setOrganization(organization());
        request.setStatus(LeaveStatus.APPROVED);
        request.setStartDate(LocalDate.of(2026, 9, 1));
        request.setEndDate(LocalDate.of(2026, 9, 3));
        return request;
    }

    private LeaveRequestCreationDto creationDto() {
        LeaveRequestCreationDto dto = new LeaveRequestCreationDto();
        dto.setLeavePolicyId(3L);
        dto.setStartDate(LocalDate.of(2026, 9, 1));
        dto.setEndDate(LocalDate.of(2026, 9, 3));
        dto.setReason("Family function");
        return dto;
    }

    /** Nobody in particular: a plain member of the tenant, holding no leave-admin role. */
    private void callerIsAPlainMember() {
        lenient().when(orgSecurity.isSelfInCurrentTenant(anyLong())).thenReturn(false);
        lenient().when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(false);
    }

    @Test
    void createRequest_isRefused_whenRaisedForSomebodyElse() {
        // The hole as it stood: a member of the tenant, naming a colleague's employee id.
        callerIsAPlainMember();

        assertThatThrownBy(() -> service().createRequest(creationDto(), COLLEAGUE_EMPLOYEE_ID))
                .isInstanceOf(AccessDeniedException.class);

        // Refused before the record is even looked up, so nothing is written and nothing is held
        // against the colleague's balance.
        verifyNoInteractions(employeeRepository, policyRepository, requestRepository);
    }

    @Test
    void createRequest_isAllowed_forYourself() {
        // The ordinary case has to keep working: this is how everyone files their own leave.
        when(orgSecurity.isSelfInCurrentTenant(CALLER_EMPLOYEE_ID)).thenReturn(true);
        when(employeeRepository.findByIdAndOrganizationId(CALLER_EMPLOYEE_ID, ORG_ID))
                .thenReturn(Optional.of(employee(CALLER_EMPLOYEE_ID)));

        // Stops at the policy lookup, which is far enough past the guard to prove it let go.
        when(policyRepository.findByIdAndOrganization_Id(3L, ORG_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().createRequest(creationDto(), CALLER_EMPLOYEE_ID))
                .isNotInstanceOf(AccessDeniedException.class);
    }

    @Test
    void createRequest_isAllowed_forSomebodyElse_byALeaveAdmin() {
        // HR filing on an employee's behalf is a real workflow and must not be caught by the fix.
        when(orgSecurity.isSelfInCurrentTenant(COLLEAGUE_EMPLOYEE_ID)).thenReturn(false);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);
        when(employeeRepository.findByIdAndOrganizationId(COLLEAGUE_EMPLOYEE_ID, ORG_ID))
                .thenReturn(Optional.of(employee(COLLEAGUE_EMPLOYEE_ID)));
        when(policyRepository.findByIdAndOrganization_Id(3L, ORG_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().createRequest(creationDto(), COLLEAGUE_EMPLOYEE_ID))
                .isNotInstanceOf(AccessDeniedException.class);
    }

    @Test
    void cancelRequest_isRefused_onSomebodyElsesRequest() {
        // The sharper half: the old guard read an employee id from the query string, so a caller
        // passing their own id could cancel any request in the tenant. Cancelling an approved one
        // restores the days to the owner's balance and wipes their leave.
        callerIsAPlainMember();
        when(requestRepository.findByIdAndOrganization_Id(REQUEST_ID, ORG_ID))
                .thenReturn(Optional.of(colleaguesApprovedRequest()));

        assertThatThrownBy(() -> service().cancelRequest(REQUEST_ID, "no longer needed"))
                .isInstanceOf(AccessDeniedException.class);

        verify(requestRepository, never()).save(any());
    }

    @Test
    void updateRequest_isRefused_onSomebodyElsesRequest() {
        callerIsAPlainMember();
        LeaveRequest draft = colleaguesApprovedRequest();
        draft.setStatus(LeaveStatus.DRAFT);
        when(requestRepository.findByIdAndOrganization_Id(REQUEST_ID, ORG_ID))
                .thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service().updateRequest(REQUEST_ID, Map.of("reason", "changed")))
                .isInstanceOf(AccessDeniedException.class);

        verify(requestRepository, never()).save(any());
    }

    @Test
    void withdrawRequest_isRefused_onSomebodyElsesRequest() {
        callerIsAPlainMember();
        LeaveRequest pending = colleaguesApprovedRequest();
        pending.setStatus(LeaveStatus.PENDING_APPROVAL);
        when(requestRepository.findByIdAndOrganization_Id(REQUEST_ID, ORG_ID))
                .thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service().withdrawRequest(REQUEST_ID))
                .isInstanceOf(AccessDeniedException.class);

        verify(requestRepository, never()).save(any());
    }

    @Test
    void submitRequest_isRefused_onSomebodyElsesRequest() {
        callerIsAPlainMember();
        LeaveRequest draft = colleaguesApprovedRequest();
        draft.setStatus(LeaveStatus.DRAFT);
        when(requestRepository.findByIdAndOrganization_Id(REQUEST_ID, ORG_ID))
                .thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service().submitRequest(REQUEST_ID))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(approvalService);
    }

    @Test
    void cancelRequest_isAllowed_onYourOwnRequest() {
        // The owner keeps the ability the fix is protecting.
        LeaveRequest own = colleaguesApprovedRequest();
        own.setEmployee(employee(CALLER_EMPLOYEE_ID));
        own.setStatus(LeaveStatus.DRAFT);
        when(orgSecurity.isSelfInCurrentTenant(CALLER_EMPLOYEE_ID)).thenReturn(true);
        when(requestRepository.findByIdAndOrganization_Id(REQUEST_ID, ORG_ID))
                .thenReturn(Optional.of(own));
        when(requestRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatCode(() -> service().cancelRequest(REQUEST_ID, "plans changed"))
                .doesNotThrowAnyException();

        verify(requestRepository).save(any());
    }
}
