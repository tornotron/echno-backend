package org.tornotron.echno_backend.employee;

/**
 * One row of {@link EmployeeRepository#findDirectPermissionsByAuthSubject}: a permission code
 * granted directly to the user in one organization.
 */
public interface OrgPermissionGrant {

    Long getOrganizationId();

    String getPermission();
}
