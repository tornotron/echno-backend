package org.tornotron.echno_backend.common.identity;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Turns a token the decoder has already validated into a {@link Principal}. One per adapter, because
 * each provider writes its claims differently. A provider's own organization, role and permission
 * claims are ignored.
 */
public interface IdentityTokenMapper {

    Principal toPrincipal(Jwt token);

    /** The provider's name, as {@code user_identity} and {@code organization_identity} store it. */
    String provider();

    /** The issuer {@link #toPrincipal} gives this provider's accounts, for a write with no token at hand. */
    String issuer();
}
