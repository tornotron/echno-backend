package org.tornotron.echno_backend.leave.enums;

import org.tornotron.echno_backend.common.enums.OrgRole;

/**
 * Which tier decides a request raised under a policy.
 *
 * <p>{@link #REPORTING_MANAGER} is the management line the approval flow has always walked, level
 * by level when the policy's multi-level toggle is on and the direct manager alone when it is off.
 * {@link #HR_ADMIN} and {@link #SYSTEM_ADMIN} route the request in a single level to an employee
 * holding that organization role, the same {@code hr-admin} and {@code system-admin} roles the leave
 * endpoints are already gated on.
 */
public enum LeaveApproverRole {
    REPORTING_MANAGER(null),
    HR_ADMIN(OrgRole.HR_ADMIN),
    SYSTEM_ADMIN(OrgRole.SYSTEM_ADMIN);

    private final OrgRole orgRole;

    LeaveApproverRole(OrgRole orgRole) {
        this.orgRole = orgRole;
    }

    /**
     * The organization role whose holders approve, or null for the management line.
     *
     * @return The org role, or null when the reporting manager decides.
     */
    public OrgRole getOrgRole() {
        return orgRole;
    }
}
