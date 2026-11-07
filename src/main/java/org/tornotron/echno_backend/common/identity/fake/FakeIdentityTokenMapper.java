package org.tornotron.echno_backend.common.identity.fake;

import org.springframework.security.oauth2.jwt.Jwt;
import org.tornotron.echno_backend.common.identity.IdentityTokenMapper;
import org.tornotron.echno_backend.common.identity.Principal;
import org.tornotron.echno_backend.common.identity.SignInMethod;

/** Reads the standard claims and, in {@code sign_in_method}, the {@link SignInMethod} a test set. */
public class FakeIdentityTokenMapper implements IdentityTokenMapper {

    static final String SIGN_IN_METHOD_CLAIM = "sign_in_method";

    @Override
    public Principal toPrincipal(Jwt token) {
        return new Principal(
                token.getClaimAsString("iss"),
                token.getSubject(),
                token.getClaimAsString("email"),
                signInMethod(token.getClaimAsString(SIGN_IN_METHOD_CLAIM)));
    }

    private static SignInMethod signInMethod(String value) {
        if (value == null) {
            return SignInMethod.UNKNOWN;
        }
        try {
            return SignInMethod.valueOf(value);
        } catch (IllegalArgumentException unreadable) {
            return SignInMethod.UNKNOWN;
        }
    }
}
