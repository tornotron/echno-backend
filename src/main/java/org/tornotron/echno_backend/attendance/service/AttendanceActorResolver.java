package org.tornotron.echno_backend.attendance.service;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.approval.ApprovalParty;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Resolves the authenticated caller into the identity the attendance module records on a
 * document: a display name, the platform user id, and the employee id where the caller has an
 * employee record in the current tenant.
 *
 * <p>It exists because every stamp in this module has to come from the session rather than from
 * the request. A regularization records who raised it and who decided it, and a movement record
 * records who verified it; all three used to be, or still could be, a value the caller supplied,
 * and a name a caller chooses is not evidence of who acted. Keeping the resolution in one bean
 * means the fallbacks below are the same wherever a stamp is written, and the self-approval rule
 * compares like with like.
 */
@Component
public class AttendanceActorResolver {

    /** The name recorded when the caller resolves to no identity at all. */
    public static final String SYSTEM_ACTOR = "system";

    private final UserContextService userContextService;
    private final EmployeeRepository employeeRepository;

    public AttendanceActorResolver(UserContextService userContextService,
                                    EmployeeRepository employeeRepository) {
        this.userContextService = userContextService;
        this.employeeRepository = employeeRepository;
    }

    /**
     * Who the authenticated caller is, as recorded on an attendance document: a display name,
     * their platform user id, and, when the caller has an employee record in this tenant, that
     * employee's id.
     *
     * @param name The name to store and show on the document.
     * @param userId The platform user id, null where the caller resolves to no user row.
     * @param employeeId The employee id in the current tenant, null where the caller has none.
     */
    public record Actor(String name, Long userId, Long employeeId) {

        /** The same identity as the self-approval rule compares parties by. */
        public ApprovalParty party() {
            return new ApprovalParty(userId, employeeId);
        }
    }

    /**
     * Resolves the authenticated caller into what gets recorded on a document.
     *
     * <p>The caller's employee record in the current organization is preferred, because the
     * employee id is what the attendance record carries and what the web client links a person
     * by. A caller with no employee record in this tenant, for instance a bootstrap administrator,
     * still gets a stable identity: their authenticated username to display, and their platform
     * user id to be compared by, which is the identity the self-approval rule falls back to when
     * there is no employee on one side. Only a caller with no identity at all falls back to
     * {@value #SYSTEM_ACTOR}, which the endpoints' authorization rules already rule out and which
     * the self-approval rule refuses to decide on rather than assuming it cannot happen.
     *
     * @return The caller's identity, never null.
     */
    public Actor resolveCurrentActor() {
        Long userId = userContextService.getCurrentUserId();
        Employee employee = userId == null ? null
                : employeeRepository.findByUserIdAndOrganizationId(userId, TenantContext.getCurrentOrgId())
                        .orElse(null);
        if (employee != null) {
            return new Actor(employee.getEmployeeName(), userId, employee.getId());
        }
        String username = userContextService.getCurrentUsername();
        return new Actor(username == null || username.isBlank() ? SYSTEM_ACTOR : username, userId, null);
    }
}
