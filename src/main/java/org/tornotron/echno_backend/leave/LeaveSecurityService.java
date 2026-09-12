package org.tornotron.echno_backend.leave;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;

/**
 * Who may read an employee's leave balances, settled against the approve grant.
 *
 * <p>A leave request is approved by whoever the employee's management line names: the line
 * manager first, then each manager above them, or a delegate one of those handed the request to.
 * None of those people usually holds an organization-wide role, so a guard written as self or
 * system-admin or hr-admin lets a line manager decide a request while refusing them the balance
 * the days come out of. That is the read-auth mismatch shape: the decision sits one tier below the
 * read it depends on, and the approver is asked to approve a figure they are not allowed to see.
 *
 * <p>The set here is therefore the approve grant plus the readers it already had. Self and the two
 * leave-administrator roles come first, because they are what the guard used to say. The rest is
 * decided against the stored employee, never against the request: the id on a balance read names
 * a person, and the people entitled to that person's balance are the ones
 * {@link LeaveApprovalService#resolveApprovalChain} would route their request to, plus anybody
 * currently holding one of their pending requests as a delegate. That second branch exists because
 * a delegate is in no management line and still has to decide.
 *
 * <p>Deliberately absent: the balance-management commands (recalculate, adjust) and the ledger by
 * balance id, which stay with the administrators. A decision needs the figure; correcting the
 * figure, and the audit of how it got there, is administration.
 */
@Slf4j
@Service("leaveSecurity")
public class LeaveSecurityService {

    private static final String[] LEAVE_ADMIN_ROLES = {"system-admin", "hr-admin"};

    private final OrganizationSecurityService orgSecurity;
    private final CurrentEmployeeService currentEmployeeService;
    private final EmployeeRepository employeeRepository;
    private final LeaveApprovalRepository approvalRepository;
    private final LeaveApprovalService approvalService;

    public LeaveSecurityService(
            OrganizationSecurityService orgSecurity,
            CurrentEmployeeService currentEmployeeService,
            EmployeeRepository employeeRepository,
            LeaveApprovalRepository approvalRepository,
            LeaveApprovalService approvalService) {
        this.orgSecurity = orgSecurity;
        this.currentEmployeeService = currentEmployeeService;
        this.employeeRepository = employeeRepository;
        this.approvalRepository = approvalRepository;
        this.approvalService = approvalService;
    }

    /**
     * Whether the caller may read this employee's leave balances.
     *
     * <p>Usage in {@code @PreAuthorize}: {@code @leaveSecurity.canViewEmployeeBalances(#employeeId)}.
     *
     * @param employeeId the employee whose balances are being read
     * @return true for the employee themselves, a system-admin or hr-admin of the current tenant,
     *     anybody in the employee's management line, or the current approver of one of the
     *     employee's pending leave requests
     */
    @Transactional(readOnly = true)
    public boolean canViewEmployeeBalances(Long employeeId) {
        if (employeeId == null) {
            return false;
        }
        if (orgSecurity.isSelfOrHasAnyOrgRole(employeeId, LEAVE_ADMIN_ROLES)) {
            return true;
        }

        Long orgId = TenantContext.getCurrentOrgId();
        if (orgId == null) {
            log.debug("No current tenant org ID set in TenantContext");
            return false;
        }
        Long callerId = currentEmployeeService.currentEmployee().map(Employee::getId).orElse(null);
        if (callerId == null) {
            return false;
        }

        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId, orgId).orElse(null);
        if (employee == null) {
            return false;
        }

        boolean inTheManagementLine = approvalService.resolveApprovalChain(employee).stream()
                .anyMatch(manager -> callerId.equals(manager.getId()));
        if (inTheManagementLine) {
            return true;
        }

        return approvalRepository.existsPendingApprovalForEmployeeByApprover(employeeId, callerId);
    }
}
