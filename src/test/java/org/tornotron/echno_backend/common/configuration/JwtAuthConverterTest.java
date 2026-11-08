package org.tornotron.echno_backend.common.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.tornotron.echno_backend.auth.AuthProperties;
import org.tornotron.echno_backend.auth.IdentityClaimsReader;
import org.tornotron.echno_backend.auth.authority.AuthorityProvider;
import org.tornotron.echno_backend.auth.authority.MembershipAuthorityProvider;
import org.tornotron.echno_backend.auth.authority.PlatformAdminAuthorityProvider;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.employee.OrgMembership;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The token decides who the caller is and nothing else; what they may do is the union of what the
 * {@link AuthorityProvider}s grant that subject. Plain unit test, no Spring context.
 */
class JwtAuthConverterTest {

    private static final AuthProperties PROPERTIES = new AuthProperties(null, null, List.of("staff-1"));

    private static Jwt token(String subject, Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        claims.forEach(builder::claim);
        return builder.build();
    }

    private static Set<String> names(AbstractAuthenticationToken authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    private static OrgMembership membership(Long organizationId, OrgRole role) {
        return new OrgMembership() {
            @Override
            public Long getOrganizationId() {
                return organizationId;
            }

            @Override
            public OrgRole getRole() {
                return role;
            }
        };
    }

    @Test
    void employeeRowsBecomeMembershipAndRoleAuthorities() {
        EmployeeRepository employees = mock(EmployeeRepository.class);
        when(employees.findMembershipsByAuthSubject("user-1")).thenReturn(List.of(
                membership(5L, OrgRole.SYSTEM_ADMIN),
                membership(5L, OrgRole.HR_ADMIN),
                membership(9L, null)));
        JwtAuthConverter converter = new JwtAuthConverter(new IdentityClaimsReader(PROPERTIES),
                List.of(new MembershipAuthorityProvider(employees), new PlatformAdminAuthorityProvider(PROPERTIES)));

        AbstractAuthenticationToken authentication = converter.convert(token("user-1", Map.of()));

        assertThat(authentication.getName()).isEqualTo("user-1");
        assertThat(names(authentication)).containsExactlyInAnyOrder(
                "ORG_MEMBER_5", "ORG_5_ROLE_system-admin", "ORG_5_ROLE_hr-admin",
                // A membership with no role is still a membership.
                "ORG_MEMBER_9");
    }

    @Test
    void groupAndRoleClaimsOnTheTokenGrantNothing() {
        // The shapes Keycloak used to put on a token. A provider's claims are not trusted for
        // authorization unless an AuthorityProvider is written to read them.
        EmployeeRepository employees = mock(EmployeeRepository.class);
        when(employees.findMembershipsByAuthSubject("user-1")).thenReturn(List.of());
        JwtAuthConverter converter = new JwtAuthConverter(new IdentityClaimsReader(PROPERTIES),
                List.of(new MembershipAuthorityProvider(employees), new PlatformAdminAuthorityProvider(PROPERTIES)));

        AbstractAuthenticationToken authentication = converter.convert(token("user-1", Map.of(
                "groups", List.of("/org-5", "/org-5/system-admin"),
                "roles", List.of("platform-admin"),
                "scope", "openid organization:admin")));

        assertThat(names(authentication)).isEmpty();
    }

    @Test
    void onlyConfiguredSubjectsArePlatformAdmins() {
        JwtAuthConverter converter = new JwtAuthConverter(new IdentityClaimsReader(PROPERTIES),
                List.of(new PlatformAdminAuthorityProvider(PROPERTIES)));

        assertThat(names(converter.convert(token("staff-1", Map.of()))))
                .containsExactly(PlatformAdminAuthorityProvider.PLATFORM_ADMIN);
        assertThat(names(converter.convert(token("user-1", Map.of())))).isEmpty();
    }

    @Test
    void everyProviderContributes() {
        AuthorityProvider fromClaim = identity -> identity.claims().containsKey("permissions")
                ? List.of(new SimpleGrantedAuthority("report:export"))
                : List.of();
        JwtAuthConverter converter = new JwtAuthConverter(new IdentityClaimsReader(PROPERTIES),
                List.of(fromClaim, new PlatformAdminAuthorityProvider(PROPERTIES)));

        AbstractAuthenticationToken authentication =
                converter.convert(token("staff-1", Map.of("permissions", List.of("x"))));

        assertThat(names(authentication))
                .containsExactlyInAnyOrder("report:export", PlatformAdminAuthorityProvider.PLATFORM_ADMIN);
    }

    @Test
    void identityClaimNamesAreConfigurable() {
        AuthProperties custom = new AuthProperties(null,
                new AuthProperties.Claims("urn:echno:email", null, null), List.of());
        IdentityClaimsReader mapper = new IdentityClaimsReader(custom);

        var identity = mapper.fromJwt(token("user-1", Map.of(
                "urn:echno:email", "a@example.com", "preferred_username", "a")));

        assertThat(identity.subject()).isEqualTo("user-1");
        assertThat(identity.email()).isEqualTo("a@example.com");
        assertThat(identity.username()).isEqualTo("a");
        assertThat(identity.name()).isNull();
    }
}
