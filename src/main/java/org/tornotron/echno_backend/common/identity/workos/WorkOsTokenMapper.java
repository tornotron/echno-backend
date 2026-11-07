package org.tornotron.echno_backend.common.identity.workos;

import org.springframework.security.oauth2.jwt.Jwt;
import org.tornotron.echno_backend.common.identity.IdentityTokenMapper;
import org.tornotron.echno_backend.common.identity.Principal;
import org.tornotron.echno_backend.common.identity.SignInMethod;

/**
 * A WorkOS access token as a {@link Principal}. Only {@code sub} is read.
 *
 * <p>Everything else in the token is left alone on purpose. {@code org_id}, {@code role},
 * {@code roles} and {@code permissions} are WorkOS's own membership, which Echno does not use, and
 * {@code org_id} is absent for a person in no organization. {@code email} is not something the token
 * shows to be verified, so linking reads the address from the Users API instead. The token carries no
 * sign-in method, so the principal's is {@link SignInMethod#UNKNOWN}, the weakest.
 *
 * <p>The decoder has already checked the signature against this Application's keys and the issuer
 * against {@code echno.security.jwt.accepted-issuers}, so every accepted token gets the same issuer:
 * the Application's default one. A custom auth domain changes the {@code iss} tokens carry, and a
 * fixed value means turning one on cannot orphan the {@code user_identity} rows.
 */
public class WorkOsTokenMapper implements IdentityTokenMapper {

    static final String PROVIDER = "workos";

    private final String issuer;

    public WorkOsTokenMapper(WorkOsSettings settings) {
        this.issuer = settings.defaultIssuer();
    }

    @Override
    public Principal toPrincipal(Jwt token) {
        return new Principal(issuer, token.getSubject(), null, SignInMethod.UNKNOWN);
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String issuer() {
        return issuer;
    }
}
