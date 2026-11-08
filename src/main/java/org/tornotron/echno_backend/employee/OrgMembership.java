package org.tornotron.echno_backend.employee;

import org.tornotron.echno_backend.common.enums.OrgRole;

/**
 * One row of {@link EmployeeRepository#findMembershipsByAuthSubject}: an organization the user
 * belongs to and one role they hold there, or a null role for a membership with none.
 */
public interface OrgMembership {

    Long getOrganizationId();

    OrgRole getRole();
}
