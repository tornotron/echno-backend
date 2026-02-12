package org.tornotron.echno_backend.common.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.user.UserContextService;

import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;

/**
 * Security service for organization-level authorization checks.
 * Used in @PreAuthorize annotations via the bean name "orgSecurity".
 *
 * Provides three levels of checks:
 *
 * 1. MEMBERSHIP: isMember(orgId)
 *    - Checks if user belongs to the org (ORG_MEMBER_{id} authority)
 *    - Source: user is in Keycloak group "org-{id}"
 *
 * 2. ORG-SCOPED ROLES: hasOrgRole(orgId, "system-admin")
 *    - Checks if user has a specific role within this org (ORG_{id}_ROLE_{role} authority)
 *    - Source: user is in Keycloak subgroup "org-{id}/system-admin"
 *
 * 3. GLOBAL PERMISSIONS: still checked via hasAuthority() directly in @PreAuthorize
 *    - e.g., hasAuthority('organization:admin') for global admins
 *
 * Example @PreAuthorize combining all three:
 *   @PreAuthorize("@orgSecurity.hasOrgRole(#id, 'system-admin') or
 *                  (hasAuthority('organization:delete') and @orgSecurity.isMember(#id)) or
 *                  hasAuthority('organization:admin')")
 */
@Service("orgSecurity")
@Slf4j
public class OrganizationSecurityService {

    private final UserContextService userContextService;
    private final EmployeeRepository employeeRepository;

    public OrganizationSecurityService(UserContextService userContextService, EmployeeRepository employeeRepository) {
        this.userContextService = userContextService;
        this.employeeRepository = employeeRepository;
    }

    /**
     * Checks if the currently authenticated user is a member of the specified organization.
     * This works by checking for the ORG_MEMBER_{orgId} authority which is extracted
     * from the "groups" claim in the JWT token by JwtAuthConverter.
     *
     * Usage in @PreAuthorize: @orgSecurity.isMember(#orgId)
     */
    public boolean isMember(Long organizationId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        String requiredAuthority = "ORG_MEMBER_" + organizationId;
        boolean result = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(requiredAuthority));
        log.debug("Organization membership check for org {}: {}", organizationId, result);
        return result;
    }

    /**
     * Checks if the user is a member of the organization OR has organization:admin authority.
     *
     * Usage in @PreAuthorize: @orgSecurity.isMemberOrAdmin(#orgId)
     */
    public boolean isMemberOrAdmin(Long organizationId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        String memberAuthority = "ORG_MEMBER_" + organizationId;
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(memberAuthority)
                        || a.getAuthority().equals("organization:admin"));
    }

    /**
     * Checks if the current user has a specific org-scoped role in the given organization.
     *
     * This checks for the authority "ORG_{orgId}_ROLE_{role}" which is extracted from
     * the JWT groups claim by JwtAuthConverter when the user belongs to a Keycloak
     * subgroup like "org-{id}/{role}".
     *
     * Example usage in @PreAuthorize:
     *   @PreAuthorize("@orgSecurity.hasOrgRole(#orgId, 'system-admin')")
     *
     * @param organizationId the organization to check the role for
     * @param role the role name (must match the Keycloak subgroup name, e.g., "system-admin")
     * @return true if the user has this role in the specified organization
     */
    public boolean hasOrgRole(Long organizationId, String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        // This authority format matches what JwtAuthConverter produces from "/org-{id}/{role}"
        String requiredAuthority = "ORG_" + organizationId + "_ROLE_" + role;
        boolean result = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(requiredAuthority));
        log.debug("Org role check for org {} role '{}': {}", organizationId, role, result);
        return result;
    }

    /**
     * Checks if the current user has ANY of the specified org-scoped roles in the given organization.
     *
     * Useful when multiple roles can perform the same action:
     *   @PreAuthorize("@orgSecurity.hasAnyOrgRole(#orgId, 'system-admin', 'hr-admin')")
     *
     * @param organizationId the organization to check roles for
     * @param roles one or more role names to check
     * @return true if the user has at least one of the specified roles in the organization
     */
    public boolean hasAnyOrgRole(Long organizationId, String... roles) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }

        Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
        boolean result = Arrays.stream(roles)
                .map(role -> "ORG_" + organizationId + "_ROLE_" + role)
                .anyMatch(required -> authorities.stream()
                        .anyMatch(a -> a.getAuthority().equals(required)));

        log.debug("Org any-role check for org {} roles {}: {}", organizationId, Arrays.toString(roles), result);
        return result;
    }

    /**
     * Checks if the current user has ANY of the specified org-scoped roles
     * for the current tenant (organization) set by TenantFilter.
     *
     * This reads the organization ID from TenantContext, which is populated
     * by the TenantFilter before @PreAuthorize evaluates.
     *
     * Usage in @PreAuthorize: @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')
     *
     * @param roles one or more role names to check
     * @return true if the user has at least one of the specified roles in the current tenant organization
     */
    public boolean hasAnyOrgRoleForCurrentTenant(String... roles) {
        Long orgId = TenantContext.getCurrentOrgId();
        if (orgId == null) {
            log.debug("No current tenant org ID set in TenantContext");
            return false;
        }
        return hasAnyOrgRole(orgId, roles);
    }

    /**
     * Checks if the current user IS the given employee, OR has any of the specified
     * org-scoped roles in the current tenant organization.
     *
     * This allows employees to perform actions on their own records while restricting
     * actions on other employees' records to users with admin roles.
     *
     * Usage in @PreAuthorize:
     *   @PreAuthorize("@orgSecurity.isSelfOrHasAnyOrgRole(#id, 'system-admin', 'hr-admin')")
     *
     * @param employeeId the employee ID to check against the current user
     * @param roles one or more admin role names that bypass the self-check
     * @return true if the current user is the employee or has an admin role
     */
    public boolean isSelfOrHasAnyOrgRole(Long employeeId, String... roles) {
        Long orgId = TenantContext.getCurrentOrgId();
        if (orgId == null) {
            log.debug("No current tenant org ID set in TenantContext");
            return false;
        }

        // Check if the current user IS this employee
        Long currentUserId = userContextService.getCurrentUserId();
        if (currentUserId != null) {
            Optional<Employee> employee = employeeRepository.findByIdAndOrganizationId(employeeId, orgId);
            if (employee.isPresent() && employee.get().getUser().getId().equals(currentUserId)) {
                log.debug("Self-check passed: user {} is employee {}", currentUserId, employeeId);
                return true;
            }
        }

        // Fall back to admin role check
        return hasAnyOrgRole(orgId, roles);
    }
}
