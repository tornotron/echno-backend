package org.tornotron.echno_backend.attendance;

import java.util.Optional;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.api.RecordFacts;

/**
 * The attendance regularisation area on the access matrix.
 *
 * <p>A regularisation belongs to the employee whose attendance it corrects, so that employee is the
 * record's owner. The starting roles hold the area two ways: every employee reads and raises at own
 * scope, which reaches only their own attendance, and the system admin, HR admin and project manager
 * hold it across the organization. A guard answers yes to both, so the record-level questions are
 * asked here: whose attendance a request is raised on or a calendar read for, and whether the caller
 * may see the whole register.
 *
 * <p>The area does not register as scope aware: the register is not narrowed by project, so a grant
 * held only on a project does not count here.
 */
@Component
public class RegularisationAccess {

    public static final String AREA = "leave-regularisation";

    private final AccessService access;
    private final CurrentEmployeeService currentEmployee;

    public RegularisationAccess(AccessService access, CurrentEmployeeService currentEmployee) {
        this.access = access;
        this.currentEmployee = currentEmployee;
    }

    /** Whether the caller may act this way on the attendance of {@code employeeId}. */
    public boolean allowsFor(String action, Long employeeId) {
        Long organizationId = TenantContext.getCurrentOrgId();
        RecordFacts record = RecordFacts.of(organizationId, null)
                .withOwners(employeeId == null ? Set.of() : Set.of(employeeId));
        return subject(organizationId)
                .map(subject -> access.check(subject, new AccessPermission(AREA, action), record))
                .orElse(false);
    }

    /** Refuses unless the caller may act this way on the attendance of {@code employeeId}. */
    public void requireFor(String action, Long employeeId, String refusal) {
        if (!allowsFor(action, employeeId)) {
            throw new AccessDeniedException(refusal);
        }
    }

    /**
     * Whether the caller may read one request: the employee whose attendance it corrects, at own
     * scope; anyone who reads regularizations across the organization; or whoever decided it.
     *
     * <p>The same three {@code AttendanceSecurityService.canViewAttendanceRecord} lets read an
     * attendance record. The decider is let in on their own account, since somebody who decided a
     * request has to be able to look at it again, whatever they hold now. Both ids are read off the
     * stored request, never off the request the caller sent.
     *
     * @param employeeId The employee whose attendance the request corrects.
     * @param approverId The employee who decided it, or null while it is pending.
     * @return Whether the caller may read it.
     */
    public boolean mayRead(Long employeeId, Long approverId) {
        if (allowsFor(AccessPermission.READ, employeeId)) {
            return true;
        }
        return approverId != null && subject(TenantContext.getCurrentOrgId())
                .map(subject -> approverId.equals(subject.employeeId()))
                .orElse(false);
    }

    /**
     * Refuses unless the caller may act this way on every employee's requests. The register and the
     * pending queue list everyone's, so a grant reaching only the caller's own attendance does not
     * open them.
     */
    public void requireAcrossOrganization(String action) {
        Long organizationId = TenantContext.getCurrentOrgId();
        boolean everything = subject(organizationId)
                .map(subject -> access.filter(subject, new AccessPermission(AREA, action)).everything())
                .orElse(false);
        if (!everything) {
            throw new AccessDeniedException("You may not read every employee's regularization requests");
        }
    }

    private Optional<AccessSubject> subject(Long organizationId) {
        if (organizationId == null) {
            return Optional.empty();
        }
        return currentEmployee.currentEmployee().map(employee -> new AccessSubject(
                organizationId,
                employee.getId(),
                employee.getUser() == null ? null : employee.getUser().getId()));
    }
}
