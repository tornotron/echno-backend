package org.tornotron.echno_backend.common.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
@Slf4j
public class JwtAuthConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtGrantedAuthoritiesConverter jwtGrantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();

    @Value("${jwt.auth.converter.resource-id}")
    private String resourceId;

    @Value("${jwt.auth.converter.principle-attribute}")
    private String principleAttribute;

    @Override
    public AbstractAuthenticationToken convert(@NonNull Jwt jwt) {
        Collection<GrantedAuthority> standardAuthorities = jwtGrantedAuthoritiesConverter.convert(jwt);
        Collection<? extends GrantedAuthority> resourceRoles = extractResourceRoles(jwt);
        Collection<? extends GrantedAuthority> permissions = extractPermissions(jwt);

        Collection<GrantedAuthority> authorities = Stream.of(
                standardAuthorities,
                resourceRoles,
                permissions
        )
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());

        String principal = getPrincipleClaimName(jwt);

        log.debug("JWT converted for principal: {}", principal);
        log.debug("Standard authorities: {}", standardAuthorities);
        log.debug("Resource roles: {}", resourceRoles);
        log.debug("Extracted permissions: {}", permissions);
        log.debug("Total authorities: {}", authorities);

        return new JwtAuthenticationToken(
                jwt,
                authorities,
                principal
        );
    }

    private String getPrincipleClaimName(Jwt jwt) {
        String claimName = JwtClaimNames.SUB;
        if(principleAttribute != null) {
            claimName = principleAttribute;
        }
        return jwt.getClaim(claimName);
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
}
