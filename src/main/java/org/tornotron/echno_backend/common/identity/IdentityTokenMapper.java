package org.tornotron.echno_backend.common.identity;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Turns a token the decoder has already validated into a {@link Principal}. One per adapter, because
 * each provider writes its claims differently. A provider's own organization, role and permission
 * claims are ignored.
 */
public interface IdentityTokenMapper {

    Principal toPrincipal(Jwt token);
}
