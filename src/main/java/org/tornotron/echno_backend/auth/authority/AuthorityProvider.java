package org.tornotron.echno_backend.auth.authority;

import org.springframework.security.core.GrantedAuthority;
import org.tornotron.echno_backend.auth.AuthenticatedIdentity;

import java.util.Collection;

/**
 * One source of the authorities an authenticated caller holds.
 *
 * <p>Every bean of this type is asked on every authenticated request, and the caller holds the
 * union of what they answer. That is the extension point for authorization: the application's own
 * memberships and roles come from {@link MembershipAuthorityProvider}, and a source that lives
 * somewhere else (a provider's permissions claim, an external policy service) is added as another
 * bean rather than by editing the converter.
 *
 * <p>The authority strings are the contract the guards are written against, so a provider must
 * mint the existing shapes rather than invent parallel ones:
 * <ul>
 *   <li>{@code ORG_MEMBER_{orgId}}: the caller belongs to the organization. {@code TenantFilter}
 *       resolves the tenant from these and nothing else.</li>
 *   <li>{@code ORG_{orgId}_ROLE_{role}}: the caller holds an {@code OrgRole} there. Always paired
 *       with the membership; a role without membership resolves no tenant and is refused
 *       everywhere.</li>
 *   <li>{@code ROLE_platform-admin}: platform staff, for the global catalogue.</li>
 * </ul>
 *
 * <p>Implementations run inside the authentication filter, before any tenant is in force, so they
 * must not load tenant-scoped entities. A projection query is fine; an entity load is refused at
 * the load boundary.
 */
@FunctionalInterface
public interface AuthorityProvider {

    /**
     * @param identity the caller, as the token describes them.
     * @return the authorities this source grants them; empty, never null, when it grants none.
     */
    Collection<? extends GrantedAuthority> authoritiesFor(AuthenticatedIdentity identity);
}
