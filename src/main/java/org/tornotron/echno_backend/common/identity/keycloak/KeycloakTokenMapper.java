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
 */
@Component
@ConditionalOnKeycloak
public class KeycloakTokenMapper implements IdentityTokenMapper {

    /** The {@code amr} values that mean a second factor was used. */
    private static final Set<String> SECOND_FACTOR = Set.of("mfa", "otp", "webauthn", "hwk", "swk");
    private static final String PASSWORD = "pwd";

    @Override
    public Principal toPrincipal(Jwt token) {
        return new Principal(
                token.getClaimAsString("iss"),
                token.getSubject(),
                token.getClaimAsString("email"),
                signInMethod(token));
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
