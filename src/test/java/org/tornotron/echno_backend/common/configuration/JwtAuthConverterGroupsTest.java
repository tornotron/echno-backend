package org.tornotron.echno_backend.common.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.tornotron.echno_backend.common.identity.IdentityTokenMapper;
import org.tornotron.echno_backend.common.identity.TokenGroups;
import org.tornotron.echno_backend.common.identity.keycloak.KeycloakTokenMapper;
import org.tornotron.echno_backend.common.identity.link.EchnoJwtAuthentication;
import org.tornotron.echno_backend.common.identity.link.MembershipAuthorities;

/**
 * What {@link JwtAuthConverter} mints: a person's organizations and roles from Echno's tables, and
 * groups only from a token the adapter still honours. The group path format is the one
 * {@code observation-producer} tokens carry.
 */
class JwtAuthConverterGroupsTest {

    private final MembershipAuthorities membership = mock(MembershipAuthorities.class);
    private final TokenGroups tokenGroups = mock(TokenGroups.class);

    @BeforeEach
    void nothingHeldByDefault() {
        when(membership.read(any())).thenReturn(MembershipAuthorities.Membership.none());
        when(tokenGroups.stillHonoured(any())).thenReturn(List.of());
    }

    private static <T> ObjectProvider<T> providerOf(T bean, Class<T> type) {
        return new StaticListableBeanFactory(Map.of("bean", bean)).getBeanProvider(type);
    }

    private static Jwt.Builder token() {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("user-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
    }

    private AbstractAuthenticationToken convert(Jwt jwt) {
        JwtAuthConverter converter =
                new JwtAuthConverter(providerOf(new KeycloakTokenMapper(), IdentityTokenMapper.class),
                providerOf(membership, MembershipAuthorities.class), providerOf(tokenGroups, TokenGroups.class));
        // No Spring context: inject the @Value fields directly. principleAttribute stays null so the
        // principal defaults to the "sub" claim.
        ReflectionTestUtils.setField(converter, "resourceId", "echno-backend-client");
        return converter.convert(jwt);
    }

    private Set<String> authorities(Jwt jwt) {
        return convert(jwt).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    @Test
    void aPersonsOrganizationsAndRolesComeFromEchno() {
        when(membership.read(any())).thenReturn(new MembershipAuthorities.Membership(7L, Set.of(
                new SimpleGrantedAuthority("ORG_MEMBER_5"), new SimpleGrantedAuthority("ORG_5_ROLE_system-admin"))));

        assertThat(authorities(token().build())).contains("ORG_MEMBER_5", "ORG_5_ROLE_system-admin");
    }

    @Test
    void groupsInAPersonsTokenCountForNothingOnceTheAdapterSaysSo() {
        Set<String> held = authorities(token().claim("groups", List.of("/org-5", "/org-5/system-admin")).build());

        assertThat(held).doesNotContain("ORG_MEMBER_5", "ORG_5_ROLE_system-admin");
    }

    @Test
    void fullGroupPathsTheAdapterHonoursBecomeMembershipAndRoles() {
        when(tokenGroups.stillHonoured(any())).thenReturn(List.of("/org-5", "/org-5/observation-producer"));

        assertThat(authorities(token().build())).contains("ORG_MEMBER_5", "ORG_5_ROLE_observation-producer");
    }

    @Test
    void aLeafGroupNameNeverBecomesARole() {
        when(tokenGroups.stillHonoured(any())).thenReturn(List.of("org-5", "system-admin"));

        assertThat(authorities(token().build())).contains("ORG_MEMBER_5").doesNotContain("ORG_5_ROLE_system-admin");
    }

    @Test
    void aProviderWithNoTokenGroupsReadsOnlyEchno() {
        JwtAuthConverter converter = new JwtAuthConverter(providerOf(new KeycloakTokenMapper(), IdentityTokenMapper.class),
                providerOf(membership, MembershipAuthorities.class),
                new StaticListableBeanFactory().getBeanProvider(TokenGroups.class));
        ReflectionTestUtils.setField(converter, "resourceId", "echno-backend-client");

        AbstractAuthenticationToken converted = converter.convert(token().claim("groups", List.of("/org-5")).build());

        assertThat(converted.getAuthorities()).isEmpty();
    }

    @Test
    void organizationAndRoleClaimsInATokenHaveNoEffect() {
        Set<String> held = authorities(token()
                .claim("org_id", "org_123")
                .claim("role", "admin")
                .claim("roles", List.of("admin"))
                .claim("permissions", List.of("assets:delete"))
                .build());

        assertThat(held).noneMatch(a -> a.startsWith("ORG_") || a.contains("admin") || a.contains("assets"));
    }

    @Test
    void theEchnoUserTravelsOnTheAuthenticationAndIsReadOnce() {
        when(membership.read(any())).thenReturn(new MembershipAuthorities.Membership(7L, Set.of()));

        AbstractAuthenticationToken converted = convert(token().build());

        assertThat(converted).isInstanceOfSatisfying(EchnoJwtAuthentication.class,
                echno -> assertThat(echno.userId()).isEqualTo(7L));
        verify(membership, times(1)).read(any());
    }
}
