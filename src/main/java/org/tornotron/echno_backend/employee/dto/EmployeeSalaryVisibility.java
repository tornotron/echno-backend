package org.tornotron.echno_backend.employee.dto;

import org.tornotron.echno_backend.common.identity.link.EchnoJwtAuthentication;
import org.tornotron.echno_backend.common.service.OrgRoleAuthority;

/**
 * Who may read an employee's pay.
 *
 * <p>{@link EmployeeDto} is the record of a colleague, and four of its fields are personal: salary,
 * date of birth, address and phone number. Three of them have an ordinary use on a site. Somebody
 * has to reach a person, somebody has to arrange transport, and some work has an age floor. Pay has
 * no such use, and it is the one field on this record whose readership is a management question
 * rather than an operational one.
 *
 * <p>The line is drawn where the module already draws it. {@code PATCH /employee/web/{id}} is
 * gated on {@code system-admin} and {@code hr-admin}; those two roles set the salary, so those two
 * roles read it. {@code project-manager} is admitted to the directory reads and not to the PATCH,
 * and it is not admitted here either. Nothing else moves: date of birth, address and phone number
 * stay visible to every caller the read itself admits, which is what keeps the directory worth
 * having.
 *
 * <p>Date of birth was the field that could have gone either way. It stays because it is used
 * (age-restricted work, birthdays) and because this record already carries a blood group and an
 * emergency contact, which are more sensitive on any honest ranking. Cutting the birth date while
 * leaving those two would be a line drawn nowhere.
 *
 * <p>A person always reads their own pay, whatever role they hold. Both single-record reads carry
 * a self branch and {@code GET /user/web/employees} returns nothing but the caller's own records,
 * so without that clause a person would be handed their own record with their own pay cut out of
 * it.
 *
 * <p>The decision is taken from thread-local state only: the authentication and the tenant scope.
 * No query, because this runs while the response is being written, and with
 * {@code spring.jpa.open-in-view} false there is no session there to run one in. Where either is
 * missing, the answer is no.
 */
final class EmployeeSalaryVisibility {

    /**
     * The roles that may set a salary through the employee PATCH, and therefore may read one back.
     */
    private static final String[] PAYROLL_ROLES = {"system-admin", "hr-admin"};

    private EmployeeSalaryVisibility() {
    }

    /**
     * @param ownerUserId the Echno user whose record is being written
     * @return whether the caller may see that person's pay
     */
    static boolean visibleTo(Long ownerUserId) {
        return isTheCaller(ownerUserId)
                || OrgRoleAuthority.heldForCurrentTenant(PAYROLL_ROLES);
    }

    /**
     * Whether the record belongs to whoever is asking. Compared on the Echno user id, which was
     * resolved when the token was converted and is on the record, so the self branch costs nothing
     * and holds across organizations: {@code GET /user/web/employees} answers with records from every
     * organization the caller belongs to, and only one of them can be the tenant in scope.
     */
    private static boolean isTheCaller(Long ownerUserId) {
        return ownerUserId != null && ownerUserId.equals(EchnoJwtAuthentication.currentUserId());
    }
}
