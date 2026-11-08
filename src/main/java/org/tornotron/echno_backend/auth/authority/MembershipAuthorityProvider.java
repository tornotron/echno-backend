package org.tornotron.echno_backend.auth.authority;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.auth.AuthenticatedIdentity;
import org.tornotron.echno_backend.common.service.OrgRoleAuthority;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.employee.OrgMembership;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Organization memberships and org-scoped roles, from the application's own database.
 *
 * <p>An employee row is a membership and the roles stored on it are the roles held there, which is
 * what the rest of the application already treated them as: the organization picker, the manager
 * listings and the low-stock sweep all read these rows. The authorities are minted from the same
 * rows, so the guards and the queries can no longer disagree, and a change made through the
 * employee endpoints is in force on the caller's next request rather than at their next token.
 *
 * <p>One query per request, by subject, returning scalars. It loads no entity, so it is untouched by
 * the tenant load boundary, which is right: it runs before the tenant is chosen, and its answer is
 * what the tenant is chosen from.
 */
@Component
@RequiredArgsConstructor
public class MembershipAuthorityProvider implements AuthorityProvider {

    private final EmployeeRepository employeeRepository;

    @Override
    public Collection<? extends GrantedAuthority> authoritiesFor(AuthenticatedIdentity identity) {
        Set<GrantedAuthority> authorities = new HashSet<>();
        for (OrgMembership membership : employeeRepository.findMembershipsByAuthSubject(identity.subject())) {
            Long organizationId = membership.getOrganizationId();
            authorities.add(new SimpleGrantedAuthority(OrgRoleAuthority.member(organizationId)));
            if (membership.getRole() != null) {
                authorities.add(new SimpleGrantedAuthority(
                        OrgRoleAuthority.of(organizationId, membership.getRole().getRoleName())));
            }
        }
        return authorities;
    }
}
