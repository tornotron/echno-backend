package org.tornotron.echno_backend.wbs;

/**
 * The two guards on every WBS endpoint, one string each so the twin controllers cannot drift.
 *
 * <p>Any member of the organization reads the schedule: the site team needs it to record progress
 * against an activity. The system administrator and the project manager shape it. Until
 * 2026-09-28 every endpoint was the administrator's alone, which left a project manager unable to
 * maintain the schedule the product expects the project team to keep.
 */
public final class WbsAccess {

    public static final String READ_GUARD = "@orgSecurity.isMemberOfCurrentTenant()";
    public static final String MANAGE_GUARD =
            "@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','project-manager')";

    private WbsAccess() {
    }
}
