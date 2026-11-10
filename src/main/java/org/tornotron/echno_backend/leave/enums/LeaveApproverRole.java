package org.tornotron.echno_backend.leave.enums;

import org.tornotron.echno_backend.modules.access.api.AccessRoleHolders;

/**
 * Which tier decides a request raised under a policy.
 *
 * <p>{@link #REPORTING_MANAGER} routes the request to the employee's immediate manager, who decides
 * it alone or transfers it to a manager above them (ClickUp 14zdkkvzx68).
 * {@link #HR_ADMIN} and {@link #SYSTEM_ADMIN} route the request in a single level to an employee
 * holding that starting role across the organization: the HR Admin and System Admin roles, which
 * stand in for the old {@code hr-admin} and {@code system-admin} roles.
 */
public enum LeaveApproverRole {
    REPORTING_MANAGER(null),
    HR_ADMIN(AccessRoleHolders.HR_ADMIN),
    SYSTEM_ADMIN(AccessRoleHolders.SYSTEM_ADMIN);

    private final String startingRole;

    LeaveApproverRole(String startingRole) {
        this.startingRole = startingRole;
    }

    /**
     * The slug of the starting role whose holders approve, or null for the management line.
     *
     * @return The template slug, or null when the reporting manager decides.
     */
    public String getStartingRole() {
        return startingRole;
    }
}
