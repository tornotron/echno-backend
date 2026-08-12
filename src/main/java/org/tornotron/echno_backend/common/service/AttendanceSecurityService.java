package org.tornotron.echno_backend.common.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Attendance authorization policy, kept in one place so the role model can be
 * retuned without touching the controllers. The controllers reference this bean
 * from {@code @PreAuthorize} (e.g. {@code @attendanceSecurity.canManageRecords()});
 * the actual role sets come from configuration and default to a sensible model:
 *
 * <ul>
 *   <li>{@code echno.security.attendance.config-roles} (default {@code system-admin,hr-admin})
 *       — who may create/update/deactivate attendance settings and shift timings.</li>
 *   <li>{@code echno.security.attendance.record-management-roles}
 *       (default {@code system-admin,hr-admin,project-manager}) — who may approve,
 *       mark-absent, delete, verify, process regularizations, and view another
 *       employee's or a whole project's records.</li>
 * </ul>
 *
 * Override either per environment (comma-separated org-role tokens) in
 * {@code application.yml} or via env vars, no code change needed. Everything else
 * (recording your own attendance, reading a record, reading the settings) only
 * requires organization membership, enforced with {@code @orgSecurity.isMemberOfCurrentTenant()}.
 */
@Service("attendanceSecurity")
public class AttendanceSecurityService {

    private final OrganizationSecurityService orgSecurity;
    private final String[] configRoles;
    private final String[] recordManagementRoles;

    public AttendanceSecurityService(
            OrganizationSecurityService orgSecurity,
            @Value("${echno.security.attendance.config-roles:system-admin,hr-admin}")
            String[] configRoles,
            @Value("${echno.security.attendance.record-management-roles:system-admin,hr-admin,project-manager}")
            String[] recordManagementRoles) {
        this.orgSecurity = orgSecurity;
        this.configRoles = configRoles;
        this.recordManagementRoles = recordManagementRoles;
    }

    /** Create/update/deactivate attendance settings and shift timings. */
    public boolean canConfigureAttendance() {
        return orgSecurity.hasAnyOrgRoleForCurrentTenant(configRoles);
    }

    /**
     * Manage attendance records: approve, mark-absent, delete, verify movements,
     * process regularizations, and view another employee's or a project's records.
     */
    public boolean canManageRecords() {
        return orgSecurity.hasAnyOrgRoleForCurrentTenant(recordManagementRoles);
    }

    /** View one employee's records: the employee themselves, or a manager / HR. */
    public boolean canViewEmployeeRecords(Long employeeId) {
        return orgSecurity.isSelfOrHasAnyOrgRole(employeeId, recordManagementRoles);
    }
}
