package org.tornotron.echno_backend.common.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service("orgSecurity")
@Slf4j
public class OrganizationSecurityService {

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
}
