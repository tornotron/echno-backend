package org.tornotron.echno_backend.modules.access.api;

import java.util.Set;

/**
 * Who holds which role in an organization: the administration side of access, beside the
 * decisions in {@link AccessService}.
 *
 * <p>Roles are named by slug, such as {@code project-manager}. Decisions never go through here:
 * a caller that wants to know whether someone may do something asks {@link AccessService}, not
 * which roles they hold.
 */
public interface AccessRoleAssignments {

    /**
     * Gives the person the role; nothing changes if they already hold it.
     *
     * @throws IllegalArgumentException when the organization has no role with that slug
     */
    void assign(Long organizationId, Long employeeId, String roleSlug);

    /** Takes the role from the person; nothing changes if they do not hold it. */
    void remove(Long organizationId, Long employeeId, String roleSlug);

    /** The slugs of the roles assigned to the person directly. */
    Set<String> rolesOf(Long organizationId, Long employeeId);
}
