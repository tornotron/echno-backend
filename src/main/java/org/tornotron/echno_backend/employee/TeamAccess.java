package org.tornotron.echno_backend.employee;

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
 * The team area on the access matrix, for the questions a guard on the area alone cannot answer.
 *
 * <p>An employee record belongs to the employee it describes, so that employee is the record's owner.
 * The starting roles hold the area two ways: every employee reads and edits their own record and
 * sees who reports to them, at own scope, while the system admin, the HR admin and the project
 * manager read across the organization, and the system admin and the HR admin also change it. A
 * guard on the area answers yes to both, so whose record a call is about is asked here.
 *
 * <p>The area does not register as scope aware: an employee is not narrowed by project, so a grant
 * held only on a project does not count here.
 */
@Component("teamAccess")
public class TeamAccess {

    public static final String AREA = "team";

    private final AccessService access;
    private final CurrentEmployeeService currentEmployee;

    public TeamAccess(AccessService access, CurrentEmployeeService currentEmployee) {
        this.access = access;
        this.currentEmployee = currentEmployee;
    }

    /** Whether the caller may act this way on the record of {@code employeeId}. */
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
     * Whether the caller may act this way on every employee's record. The directory, the manager list
     * and the changes that reach past one's own record cover everyone, so a grant reaching only the
     * caller's own record does not open them.
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
