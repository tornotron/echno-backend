package org.tornotron.echno_backend.common.identity.keycloak;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.identity.ConditionalOnKeycloak;
import org.tornotron.echno_backend.common.identity.IdentityTokenMapper;
import org.tornotron.echno_backend.common.identity.Principal;
import org.tornotron.echno_backend.common.identity.SignInMethod;

import java.util.List;
import java.util.Set;

/**
 * Keycloak's token as a {@link Principal}. The sign-in method comes from the standard {@code amr}
 * claim (RFC 8176), which Keycloak only sends when the realm's client scope has that mapper, so a
 * missing value is the normal case and counts as the weakest.
 *
 * <p>The issuer in a Keycloak token changes with the address it was asked through: the internal and
 * public addresses of one realm give two values, and {@code echno.security.jwt.accepted-issuers} lists
 * both. The decoder has already checked the signature against the one realm this deployment has, so
 * every accepted token gets the same {@link #ISSUER}. It is a fixed name and not an address, so moving
 * a hostname cannot orphan the {@code user_identity} rows.
 */
@Component
@ConditionalOnKeycloak
public class KeycloakTokenMapper implements IdentityTokenMapper {

    /** The {@code amr} values that mean a second factor was used. */
    private static final Set<String> SECOND_FACTOR = Set.of("mfa", "otp", "webauthn", "hwk", "swk");
    private static final String PASSWORD = "pwd";

    static final String PROVIDER = "keycloak";
    static final String ISSUER = "keycloak";

    @Override
    public Principal toPrincipal(Jwt token) {
        return new Principal(
                ISSUER,
                token.getSubject(),
                token.getClaimAsString("email"),
                signInMethod(token));
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String issuer() {
        return ISSUER;
    }

    private static SignInMethod signInMethod(Jwt token) {
        if (!(token.getClaim("amr") instanceof List<?> methods)) {
            return SignInMethod.UNKNOWN;
        }
        if (methods.stream().anyMatch(SECOND_FACTOR::contains)) {
            return SignInMethod.MFA;
        }
        return methods.contains(PASSWORD) ? SignInMethod.PASSWORD : SignInMethod.UNKNOWN;
    }
}
