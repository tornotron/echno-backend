package org.tornotron.echno_backend.common.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.identity.IdentityTokenMapper;
import org.tornotron.echno_backend.common.identity.TokenGroups;
import org.tornotron.echno_backend.common.identity.link.EchnoJwtAuthentication;
import org.tornotron.echno_backend.common.identity.link.MembershipAuthorities;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
@Slf4j
public class JwtAuthConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtGrantedAuthoritiesConverter jwtGrantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
    private final ObjectProvider<IdentityTokenMapper> mapper;
    private final ObjectProvider<MembershipAuthorities> membershipAuthorities;
    private final ObjectProvider<TokenGroups> tokenGroups;

    @Value("${jwt.auth.converter.resource-id}")
    private String resourceId;

    @Value("${jwt.auth.converter.principle-attribute}")
    private String principleAttribute;

    /**
     * Providers because every web slice test loads this class, as a {@code Converter}, and authenticates
     * directly. Nothing here is needed until a real token arrives, and production always has the first
     * two. Only a provider that carries groups in its tokens has the third.
     */
    public JwtAuthConverter(ObjectProvider<IdentityTokenMapper> mapper,
                            ObjectProvider<MembershipAuthorities> membershipAuthorities,
                            ObjectProvider<TokenGroups> tokenGroups) {
        this.mapper = mapper;
        this.membershipAuthorities = membershipAuthorities;
        this.tokenGroups = tokenGroups;
    }

    @Override
    public AbstractAuthenticationToken convert(@NonNull Jwt jwt) {
        Collection<GrantedAuthority> standardAuthorities = jwtGrantedAuthoritiesConverter.convert(jwt);
        Collection<? extends GrantedAuthority> resourceRoles = extractResourceRoles(jwt);
        Collection<? extends GrantedAuthority> permissions = extractPermissions(jwt);
        Collection<? extends GrantedAuthority> groupAuthorities = extractGroupAuthorities(jwt);
        MembershipAuthorities.Membership membership = membershipAuthorities.getObject().read(mapper.getObject().toPrincipal(jwt));

        Collection<GrantedAuthority> authorities = Stream.of(
                standardAuthorities,
                resourceRoles,
                permissions,
                groupAuthorities,
                membership.authorities()
        )
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());

        String principal = getPrincipleClaimName(jwt);

        log.debug("JWT converted for principal: {}", principal);
        log.debug("Standard authorities: {}", standardAuthorities);
        log.debug("Resource roles: {}", resourceRoles);
        log.debug("Extracted permissions: {}", permissions);
        log.debug("Group authorities still honoured from the token: {}", groupAuthorities);
        log.debug("Authorities from Echno's tables: {}", membership.authorities());
        log.debug("Total authorities: {}", authorities);

        return new EchnoJwtAuthentication(jwt, authorities, principal, membership.userId());
    }

    /**
     * The configured claim, falling back to {@code sub} when the token has no such claim. WorkOS
     * tokens carry no {@code preferred_username}.
     */
    private String getPrincipleClaimName(Jwt jwt) {
        if (principleAttribute != null) {
            String name = jwt.getClaimAsString(principleAttribute);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return jwt.getClaimAsString(JwtClaimNames.SUB);
    }

    private Collection<? extends GrantedAuthority> extractResourceRoles(Jwt jwt) {
        Map<String, Object> resourceAccess = jwt.getClaim("resource_access");
        if (resourceAccess == null || resourceAccess.get(resourceId) == null) {
            return Set.of();
        }

        Map<String, Object> resource =
                (Map<String, Object>) resourceAccess.get(resourceId);

        Collection<String> roles =
                (Collection<String>) resource.get("roles");

        return roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toSet());
    }



    private Collection<? extends GrantedAuthority> extractPermissions(Jwt jwt) {
        Map<String, Object> authorization = jwt.getClaim("authorization");
        if(authorization == null) {
            log.debug("No 'authorization' claim found in JWT - RPT token may not have been obtained");
            return Set.of();
        }

        Collection<Map<String, Object>> permissions =
                (Collection<Map<String, Object>>) authorization.get("permissions");

        if (permissions == null) {
            log.debug("No 'permissions' found in authorization claim");
            return Set.of();
        }

        log.debug("Found {} permission(s) in authorization claim", permissions.size());

        return permissions.stream()
                .flatMap(permission -> {
                    String resource = (String) permission.get("rsname");
                    Collection<String> scopes = (Collection<String>) permission.get("scopes");

                    if(scopes == null) {
                        log.debug("No scopes found for resource: {}", resource);
                        return Stream.empty();
                    }

                    log.debug("Resource '{}' has scopes: {}", resource, scopes);

                    return scopes.stream()
                            .map(scope ->
                                    new SimpleGrantedAuthority(resource + ":" + scope));
                })
                .collect(Collectors.toSet());
    }

    /**
     * Extracts organization-related authorities from the groups in the token that still count, which
     * is only a machine login's once Echno's tables are complete. See {@link TokenGroups}.
     *
     * Keycloak groups follow this structure:
     *   /org-{id}                → flat membership group   → authority: ORG_MEMBER_{id}
     *   /org-{id}/{role-name}    → role subgroup           → authority: ORG_{id}_ROLE_{role-name}
     *
     * Examples:
     *   "/org-5"               → ORG_MEMBER_5
     *   "/org-5/system-admin"  → ORG_5_ROLE_system-admin
     *   "/org-10/hr-admin"     → ORG_10_ROLE_hr-admin
     *
     * The ORG_MEMBER_* authorities represent simple org membership (existing behavior).
     * The ORG_*_ROLE_* authorities represent org-scoped roles (new behavior).
     * Both are extracted in a single pass over the groups claim.
     */
    private Collection<? extends GrantedAuthority> extractGroupAuthorities(Jwt jwt) {
        TokenGroups source = tokenGroups.getIfAvailable();
        List<String> groups = source == null ? List.of() : source.stillHonoured(jwt);
        if (groups.isEmpty()) {
            return Set.of();
        }

        log.debug("Found {} group(s) in JWT: {}", groups.size(), groups);

        return groups.stream()
                .map(group -> group.startsWith("/") ? group.substring(1) : group)
                .filter(group -> group.startsWith("org-"))
                .flatMap(group -> {
                    // Find the first "/" after the "org-" prefix — this separates org ID from role name
                    int slashIndex = group.indexOf('/');

                    if (slashIndex == -1) {
                        // No slash → flat org group like "org-5"
                        // This is plain membership, same as before
                        String orgId = group.substring(4); // Remove "org-" prefix
                        log.debug("Org membership: {} → ORG_MEMBER_{}", group, orgId);
                        return Stream.of(new SimpleGrantedAuthority("ORG_MEMBER_" + orgId));
                    } else {
                        // Has slash → role subgroup like "org-5/system-admin"
                        // orgId is between "org-" and the slash, role is after the slash
                        String orgId = group.substring(4, slashIndex);
                        String role = group.substring(slashIndex + 1);
                        String authority = "ORG_" + orgId + "_ROLE_" + role;
                        log.debug("Org role: {} → {}", group, authority);
                        return Stream.of(new SimpleGrantedAuthority(authority));
                    }
                })
                .collect(Collectors.toSet());
    }
}
