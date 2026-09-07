package org.tornotron.echno_backend.common.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;

import java.util.Arrays;
import java.util.Collection;

/**
 * The org-scoped role authority, written down once.
 *
 * <p>{@code JwtAuthConverter} mints {@code ORG_{orgId}_ROLE_{role}} from the Keycloak group path
 * {@code /org-{orgId}/{role}}, and everything that asks whether a caller holds a role in an
 * organization is comparing against that string. {@link OrganizationSecurityService} answers that
 * question for {@code @PreAuthorize} and remains the only way to ask it there. This class exists
 * because the same question is now also asked outside a guard, while a response body is being
 * written, where there is no bean to inject. Both paths go through the format below rather than
 * through two copies of it.
 *
 * <p>Everything read here is a thread local: the {@link SecurityContextHolder} authentication and
 * the {@link TenantContext} organization id. Both are set for the whole of a request, the response
 * body included, because {@code TenantFilter} is a servlet filter and clears the scope only after
 * the dispatcher has written the body. On a thread with neither, the answer is false.
 */
public final class OrgRoleAuthority {

    private OrgRoleAuthority() {
    }

    /** The authority a caller carries when they hold {@code role} in {@code organizationId}. */
    public static String of(Long organizationId, String role) {
        return "ORG_" + organizationId + "_ROLE_" + role;
    }

    /**
     * Whether the caller on this thread holds any of {@code roles} in the organization the thread
     * is scoped to.
     *
     * <p>False when there is no authentication, and false when no organization id is in scope. The
     * second case includes the global-admin bypass, which sets no organization id: a bypass session
     * reads across tenants but holds no role in any one of them, and this answers accordingly. That
     * matches {@code hasAnyOrgRoleForCurrentTenant}, which refuses the same caller for the same
     * reason, so a bypass does not quietly become a wider grant here than it is at a guard.
     */
    public static boolean heldForCurrentTenant(String... roles) {
        Long organizationId = TenantContext.getCurrentOrgId();
        if (organizationId == null) {
            return false;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        return Arrays.stream(roles)
                .map(role -> of(organizationId, role))
                .anyMatch(required -> authorities.stream()
                        .anyMatch(authority -> authority.getAuthority().equals(required)));
    }
}
