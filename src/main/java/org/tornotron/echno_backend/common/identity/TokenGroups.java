package org.tornotron.echno_backend.common.identity;

import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Optional: the group paths in a token that still decide access, for a provider that carries them.
 * Only the Keycloak adapter has one. Everyone else gets their organizations and roles from Echno.
 */
public interface TokenGroups {

    List<String> stillHonoured(Jwt token);
}
