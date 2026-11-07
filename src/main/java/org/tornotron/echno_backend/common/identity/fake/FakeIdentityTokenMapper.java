package org.tornotron.echno_backend.common.identity.fake;

import org.springframework.security.oauth2.jwt.Jwt;
import org.tornotron.echno_backend.common.identity.IdentityTokenMapper;
import org.tornotron.echno_backend.common.identity.Principal;
import org.tornotron.echno_backend.common.identity.SignInMethod;

/**
 * Reads the standard claims and, in {@code sign_in_method}, the {@link SignInMethod} a test set. A
 * token with no issuer, as a test's often is, counts as {@link #ISSUER}.
 */
public class FakeIdentityTokenMapper implements IdentityTokenMapper {

    static final String SIGN_IN_METHOD_CLAIM = "sign_in_method";
    static final String PROVIDER = "fake";
    static final String ISSUER = "https://fake-identity.test";

    @Override
    public Principal toPrincipal(Jwt token) {
        String issuer = token.getClaimAsString("iss");
        return new Principal(
                issuer == null ? ISSUER : issuer,
                token.getSubject(),
                token.getClaimAsString("email"),
                signInMethod(token.getClaimAsString(SIGN_IN_METHOD_CLAIM)));
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String issuer() {
        return ISSUER;
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
