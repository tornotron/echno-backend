package org.tornotron.echno_backend.common.service;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.user.UserContextService;

import java.util.Optional;

/**
 * Resolves the authenticated caller to their {@link Employee} record in the current tenant.
 *
 * <p>This exists so that a field naming who did something can be filled in from the session
 * rather than from the request. A record that says who wrote it, raised it or approved it is read
 * as that person's own statement, and where that id comes off the payload it is whatever the
 * caller typed: the guard on the endpoint answers whether the caller may act at all, never
 * whether the name on the record is theirs.
 *
 * <p>Chat, attendance and leave each grew their own copy of this lookup. This is the one the
 * attribution fields share, so "who is calling" is answered the same way everywhere and a new
 * endpoint has something to reach for.
 */
@Service
public class CurrentEmployeeService {

    private final UserContextService userContextService;
    private final EmployeeRepository employeeRepository;

    public CurrentEmployeeService(UserContextService userContextService,
                                  EmployeeRepository employeeRepository) {
        this.userContextService = userContextService;
        this.employeeRepository = employeeRepository;
    }

    /**
     * The caller's employee record in the current tenant, empty when the session resolves to no
     * user row or that user has no employee record in this organization.
     *
     * <p>Both are ordinary states rather than faults. {@code UserContextService} resolves the JWT
     * subject against {@code users_table.keycloak_id}, and an account created straight in the
     * Keycloak console has no such row; an account that does have one may still hold no employee
     * record in this organization, which is the shape a bootstrap administrator has.
     */
    public Optional<Employee> currentEmployee() {
        Long userId = userContextService.getCurrentUserId();
        if (userId == null) {
            return Optional.empty();
        }
        return employeeRepository.findByUserIdAndOrganizationId(userId, TenantContext.getCurrentOrgId());
    }

    /**
     * The caller's employee record, or a refusal naming what could not be done without one.
     *
     * <p>Failing closed is the point. The alternative, letting the caller name themselves, is how
     * these fields worked before: an account with no employee record could post a comment, raise
     * an issue or act on an approval under any colleague's id, which is the forgery this lookup
     * removes. Chat already refuses the same caller for the same reason, and the self-approval
     * rule refuses an approval that would name nobody. An account that needs to do these things
     * is given an employee record in the organization, which is what every other employee-centric
     * part of the product already asks of it.
     *
     * @param action What the caller was trying to do, named in the refusal, for example
     *     "comment on an issue".
     * @return The caller's employee record in the current tenant.
     * @throws AccessDeniedException if the caller has no employee record here.
     */
    public Employee requireCurrentEmployee(String action) {
        return currentEmployee().orElseThrow(() -> new AccessDeniedException(
                "You have no employee record in this organization, so there is nobody to record as "
                        + "having done this. Ask an administrator to add you to the organization as an "
                        + "employee before you " + action + "."));
    }
}
