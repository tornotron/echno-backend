package org.tornotron.echno_backend.common.identity.link;

import java.util.Collection;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * A signed-in request, carrying the Echno user the token resolved to when it was first looked at, so
 * nothing later in the request has to query for it. That matters most while a response is being
 * written, where there is no session to query in.
 */
public class EchnoJwtAuthentication extends JwtAuthenticationToken {

    private final transient Long userId;

    public EchnoJwtAuthentication(Jwt jwt, Collection<? extends GrantedAuthority> authorities, String name,
                                  @Nullable Long userId) {
        super(jwt, authorities, name);
        this.userId = userId;
    }

    /** The Echno user, or null when this account has none yet. */
    @Nullable
    public Long userId() {
        return userId;
    }

    /** The current request's Echno user, or null when it does not have one. */
    @Nullable
    public static Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof EchnoJwtAuthentication echno && echno.isAuthenticated()
                ? echno.userId()
                : null;
    }
}
