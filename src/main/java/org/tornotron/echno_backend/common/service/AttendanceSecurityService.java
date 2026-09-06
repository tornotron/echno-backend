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

    /**
     * View one stored attendance record, and the movement trail hanging off it.
     *
     * <p>The people who may read an employee's attendance are settled by
     * {@link #canViewEmployeeRecords}, plus the approver the record itself names. That addition is
     * the same one {@link #canDecideApproval} makes and is there for the same reason: a geofence
     * exception is decided by the employee's reporting manager, who is usually not a holder of any
     * organization-wide role. Somebody asked to decide a record has to be able to look at it, and
     * without this branch the deciding would have been allowed while the reading was refused.
     *
     * <p>Both ids are read off the stored record, never off the request. A record-level check is
     * what the by-id reads need, because the id on the request names a record rather than a person
     * and so says nothing about who is entitled to it.
     *
     * @param employeeId The employee the record belongs to.
     * @param designatedApproverId The approver the record names, or null when none is.
     * @return Whether the caller may read it.
     */
    public boolean canViewAttendanceRecord(Long employeeId, Long designatedApproverId) {
        return canViewEmployeeRecords(employeeId)
                || (designatedApproverId != null && orgSecurity.isSelfInCurrentTenant(designatedApproverId));
    }

    /**
     * Record or correct attendance for one employee: the employee themselves, or a manager / HR.
     *
     * <p>Same policy as {@link #canViewEmployeeRecords}, named separately because the write
     * endpoints resolve the employee from the stored record (or, on a check-in, from the payload)
     * in the service rather than from an annotation: the id on the request is the caller's word,
     * not evidence, so the {@code @PreAuthorize} guard on those handlers only checks tenant
     * membership and the services call this with the id the record actually names.
     */
    public boolean canRecordFor(Long employeeId) {
        return orgSecurity.isSelfOrHasAnyOrgRole(employeeId, recordManagementRoles);
    }

    /**
     * Whether the caller is the employee whose attendance is being recorded, rather than someone
     * recording it on their behalf.
     *
     * <p>{@link #canRecordFor} deliberately blurs the two, because both are allowed to write. The
     * geofence rule needs them apart: a punch an employee takes on their own account is evaluated
     * against the site they are marking against, and being outside it makes them explain
     * themselves. A supervisor entering a team's attendance is sending their own device's position
     * for somebody else's day, so the same demand would be asking the wrong person about the wrong
     * location.
     *
     * @param employeeId The employee the record belongs to.
     * @return Whether the caller is that employee.
     */
    public boolean isSelfMarking(Long employeeId) {
        return orgSecurity.isSelfInCurrentTenant(employeeId);
    }

    /**
     * Whether the caller may decide an attendance record's approval.
     *
     * <p>The record-management roles decide every attendance record and continue to. This adds the
     * employee a geofence exception names as its approver, which is the reporting manager the
     * decision is meant to rest with and who is often not a manager in the role sense: the role set
     * is organization-wide, so gating on it alone would have sent every exception to HR and the
     * project managers regardless of who the employee reports to.
     *
     * <p>The designated approver is read off the stored record, never off the request. Passing an
     * id a caller supplied would let anyone nominate themselves.
     *
     * @param designatedApproverId The approver named on the record, or null when none is.
     * @return Whether the caller may decide it.
     */
    public boolean canDecideApproval(Long designatedApproverId) {
        return canManageRecords()
                || (designatedApproverId != null && orgSecurity.isSelfInCurrentTenant(designatedApproverId));
    }
}
