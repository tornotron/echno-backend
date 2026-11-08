package org.tornotron.echno_backend.auth;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Reads an {@link AuthenticatedIdentity} off a validated JWT, using the claim names in
 * {@link AuthProperties.Claims}.
 */
@Component
public class IdentityClaimsReader {

    private final AuthProperties.Claims claims;

    public IdentityClaimsReader(AuthProperties properties) {
        this.claims = properties.claims();
    }

    public AuthenticatedIdentity fromJwt(Jwt jwt) {
        return new AuthenticatedIdentity(
                jwt.getSubject(),
                jwt.getClaimAsString(claims.email()),
                jwt.getClaimAsString(claims.username()),
                jwt.getClaimAsString(claims.name()),
                jwt.getClaims());
    }
}
