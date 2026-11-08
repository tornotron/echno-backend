package org.tornotron.echno_backend.common.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.tornotron.echno_backend.auth.AuthenticatedIdentity;
import org.tornotron.echno_backend.auth.IdentityClaimsReader;
import org.tornotron.echno_backend.auth.authority.AuthorityProvider;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a validated JWT into the request's authentication.
 *
 * <p>The token is trusted for one thing, who the caller is. Its subject becomes the
 * authentication's name, and the authorities are whatever the {@link AuthorityProvider}s grant
 * that subject, so nothing a provider puts on the token decides what the caller may do unless a
 * provider is written to read it.
 *
 * <p>Deliberately not a bean. A {@code @WebMvcTest} slice picks up every {@code Converter} bean,
 * and this one reaches the database through its providers, which no slice has. The slices
 * authenticate with {@code jwt()} and never need it; {@link SecurityConfig} builds it for the real
 * filter chain.
 */
@Slf4j
public class JwtAuthConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final IdentityClaimsReader identityReader;
    private final List<AuthorityProvider> authorityProviders;

    public JwtAuthConverter(IdentityClaimsReader identityReader, List<AuthorityProvider> authorityProviders) {
        this.identityReader = identityReader;
        this.authorityProviders = List.copyOf(authorityProviders);
    }

    @Override
    public AbstractAuthenticationToken convert(@NonNull Jwt jwt) {
        AuthenticatedIdentity identity = identityReader.fromJwt(jwt);

        Set<GrantedAuthority> authorities = new HashSet<>();
        for (AuthorityProvider provider : authorityProviders) {
            authorities.addAll(provider.authoritiesFor(identity));
        }

        log.debug("JWT converted for subject {} with authorities {}", identity.subject(), authorities);
        return new JwtAuthenticationToken(jwt, authorities, identity.subject());
    }
}
