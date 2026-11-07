package org.tornotron.echno_backend.common.identity;

import org.tornotron.echno_backend.common.enums.OrgRole;

/**
 * Optional extra: a provider that keeps its own copy of each person's organization roles. Only the
 * Keycloak adapter has it, as subgroups under the organization's group. Reach it through
 * {@link IdentityProvider#capabilities()}. It goes when no check reads those subgroups any more.
 */
public interface OrgRoleGroups {

    /** Records the role, and membership with it, since a role without membership resolves nothing. */
    void assign(ExternalUserId user, EchnoOrgId organization, OrgRole role);

    /** Removes the role and leaves membership alone. */
    void remove(ExternalUserId user, EchnoOrgId organization, OrgRole role);
}
