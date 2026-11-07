package org.tornotron.echno_backend.organization;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Who may start a new organization. With {@code echno.identity.self-service-organizations} on, any
 * signed-in person may, which is how an account on the stagings starts its own tenant. With it off,
 * only a platform administrator may: a stack where people arrive only by invitation has nobody else
 * who should.
 *
 * <p>Unset means on under Keycloak, so the stagings keep self-service with no config change, and off
 * under WorkOS.
 */
@Component("organizationCreation")
public class OrganizationCreationPolicy {

    static final String PLATFORM_ADMIN = "ROLE_platform-admin";

    private final boolean selfService;

    public OrganizationCreationPolicy(@Value("${echno.identity.self-service-organizations:}") String setting,
                                      @Value("${echno.identity.provider:keycloak}") String provider) {
        this.selfService = setting == null || setting.isBlank()
                ? !"workos".equals(provider.trim())
                : Boolean.parseBoolean(setting.trim());
    }

    public boolean selfService() {
        return selfService;
    }

    /** For the create routes' guard. */
    public boolean allows(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return selfService || authentication.getAuthorities().stream()
                .anyMatch(authority -> PLATFORM_ADMIN.equals(authority.getAuthority()));
    }
}
