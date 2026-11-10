package org.tornotron.echno_backend.modules.access.api;

import java.util.Collection;
import java.util.List;

/**
 * Who holds a starting role, for the few places that name a role on purpose instead of asking for a
 * permission: the tier a leave policy routes to, the people a store's notices go to, the approver of a
 * punch outside the fence, and the manager listings.
 *
 * <p>A role here is one the organization was given from a template, named by the template's slug. A
 * role the organization made itself never counts, whatever it is called or holds. Answers are in
 * employee id order and do not look at the employee's status, as the role lists they replace did not.
 */
public interface AccessRoleHolders {

    String SYSTEM_ADMIN = "system-admin";
    String ORG_MANAGER = "org-manager";
    String HR_ADMIN = "hr-admin";
    String PROJECT_MANAGER = "project-manager";
    String STORE_KEEPER = "store-keeper";

    /**
     * The employees of the organization who hold one of these starting roles across it.
     *
     * @param organizationId the organization the employees and their roles belong to
     * @param roles template slugs; empty answers empty
     */
    List<Long> acrossOrganization(Long organizationId, Collection<String> roles);

    /**
     * The members of the project (on its employee list) who hold this starting role, across the
     * organization or on that project.
     *
     * @param organizationId the organization the employees and their roles belong to
     * @param projectId the project whose employee list they must be on
     * @param role a template slug
     */
    List<Long> onProject(Long organizationId, Long projectId, String role);
}
