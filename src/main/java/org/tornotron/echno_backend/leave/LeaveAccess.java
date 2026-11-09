package org.tornotron.echno_backend.leave;

import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessService;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.api.RecordFacts;

/**
 * The leave area on the access matrix, for the questions a guard on the area alone cannot answer.
 *
 * <p>Leave belongs to the employee taking it, so that employee is the record's owner. The starting
 * roles hold the area two ways: every employee reads, raises, changes and decides at own scope, and
 * the system admin and HR admin read, raise and change across the organization. A guard on the area
 * answers yes to both, so whose leave a call is about is asked here, against the employee the stored
 * record names wherever there is one.
 *
 * <p>The area does not register as scope aware: leave is not narrowed by project, so a grant held
 * only on a project does not count here.
 */
@Component("leaveAccess")
public class LeaveAccess {

    public static final String AREA = "leave";

    private final AccessService access;
    private final CurrentEmployeeService currentEmployee;

    public LeaveAccess(AccessService access, CurrentEmployeeService currentEmployee) {
        this.access = access;
        this.currentEmployee = currentEmployee;
    }

    /** Whether the caller may act this way on the leave of {@code employeeId}. */
    public boolean allowsFor(String action, Long employeeId) {
        if (employeeId == null) {
            return false;
        }
        Long organizationId = TenantContext.getCurrentOrgId();
        RecordFacts record = RecordFacts.of(organizationId, null).withOwners(Set.of(employeeId));
        return subject(organizationId)
                .map(subject -> access.check(subject, new AccessPermission(AREA, action), record))
                .orElse(false);
    }

    /**
     * Whether the caller may act this way on every employee's leave. The organization's calendar,
     * the request lists by status and the balance ledger cover everyone, so a grant reaching only the
     * caller's own leave does not open them.
     */
    public boolean acrossOrganization(String action) {
        return subject(TenantContext.getCurrentOrgId())
                .map(subject -> access.filter(subject, new AccessPermission(AREA, action)).everything())
                .orElse(false);
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
