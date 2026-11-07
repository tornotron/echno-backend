package org.tornotron.echno_backend.modules.access.api;

import java.util.UUID;
import org.springframework.lang.Nullable;
import org.tornotron.echno_backend.common.enums.OrgRole;

/**
 * The one way a person's roles change, so every rule about it holds whichever screen or endpoint asked.
 * Joins the caller's transaction, and always works in the current tenant.
 *
 * <p>Every change refuses when the caller may not make it: nobody changes their own roles, a caller
 * assigns only what their own roles may assign, and an organization always keeps an administrator.
 * Doing what is already done changes nothing.
 */
public interface AccessRoleAssignments {

    /** Gives the person the role that stands in for this old role, across the organization. */
    void assignOrgRole(Long employeeId, OrgRole role);

    void removeOrgRole(Long employeeId, OrgRole role);

    /**
     * The same, made by the system and so outside the caller's rules: the first administrator of a new
     * organization has nobody to be given the role by. An organization still keeps an administrator.
     */
    void assignOrgRoleAsSystem(Long employeeId, OrgRole role);

    /**
     * Gives the person a role, on a project or, with none, across the organization.
     *
     * @throws org.tornotron.echno_backend.common.exception.InvalidRequestException if a role with an old
     *         counterpart is given a project, since the old checks would grant it everywhere.
     */
    void assign(Long employeeId, UUID roleId, @Nullable Long projectId);

    void remove(Long employeeId, UUID roleId, @Nullable Long projectId);

    /**
     * Refuses when the person is the organization's last active administrator, for a change that takes
     * them out of it some other way, such as a status change or leaving.
     */
    void refuseIfLastAdministrator(Long employeeId);
}
