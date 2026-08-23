package org.tornotron.echno_backend.common.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract guard for {@link JwtAuthConverter}'s group-authority mapping (audit item H-4).
 *
 * <p>The org-scoped RBAC model depends on the Keycloak group mapper emitting FULL group paths in the
 * top-level {@code groups} claim, e.g. {@code /org-5} and {@code /org-5/system-admin}. If that mapper
 * is ever reconfigured to emit only leaf names, org membership and org-scoped roles silently vanish
 * and every org-scoped {@code @PreAuthorize} check fails open or closed without any compile error.
 * These tests lock the exact authorities the converter derives from full paths.
 */
class JwtAuthConverterGroupsTest {

    private static Jwt jwtWithGroups(List<String> groups) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("user-1")
                .claim("groups", groups)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    private static Set<String> authorities(Jwt jwt) {
        JwtAuthConverter converter = new JwtAuthConverter();
        // No Spring context: inject the @Value fields directly. principleAttribute stays null so the
        // principal defaults to the "sub" claim.
        ReflectionTestUtils.setField(converter, "resourceId", "echno-backend-client");

        AbstractAuthenticationToken auth = converter.convert(jwt);
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    @Test
    void fullGroupPaths_yieldMembershipAndOrgScopedRoleAuthorities() {
        Set<String> authorities = authorities(jwtWithGroups(List.of("/org-5", "/org-5/system-admin")));

        // /org-5            -> ORG_MEMBER_5           (plain org membership)
        // /org-5/system-admin -> ORG_5_ROLE_system-admin (org-scoped role)
        assertTrue(authorities.contains("ORG_MEMBER_5"),
                "expected org membership authority ORG_MEMBER_5, got " + authorities);
        assertTrue(authorities.contains("ORG_5_ROLE_system-admin"),
                "expected org-scoped role authority ORG_5_ROLE_system-admin, got " + authorities);
    }

    @Test
    void leafOnlyGroupNames_yieldNoOrgAuthorities() {
        // What a broken mapper emits: leaf names instead of full paths. This must produce NO org
        // authorities, which is exactly the regression this guard is here to catch.
        Set<String> authorities = authorities(jwtWithGroups(List.of("org-5", "system-admin")));

        assertTrue(authorities.contains("ORG_MEMBER_5"),
                "a flat 'org-5' is still membership, got " + authorities);
        assertFalse(authorities.contains("ORG_5_ROLE_system-admin"),
                "a leaf 'system-admin' must not resolve to an org-scoped role, got " + authorities);
    }
}
