package org.tornotron.echno_backend.common.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the JWT audience and issuer validators. No Spring context: the
 * validators are exercised directly against hand-built {@link Jwt} tokens.
 */
class JwtDecoderConfigTest {

    private static final String BACKEND_CLIENT = "echno-backend-client";
    private static final String PUBLIC_ISSUER = "https://auth.echno.in/realms/echno-realm";
    private static final String INTERNAL_ISSUER = "http://echno-keycloak:8080/realms/echno-realm";

    private Jwt.Builder token() {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
    }

    // --- Audience ---------------------------------------------------------

    @Test
    void audienceValidator_acceptsTokenWithRequiredAudience() {
        var validator = new JwtDecoderConfig.AudienceValidator(BACKEND_CLIENT);
        Jwt jwt = token().audience(List.of(BACKEND_CLIENT, "account")).build();

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertFalse(result.hasErrors());
    }

    @Test
    void audienceValidator_rejectsTokenMintedForAnotherClient() {
        var validator = new JwtDecoderConfig.AudienceValidator(BACKEND_CLIENT);
        Jwt jwt = token().audience(List.of("some-other-client")).build();

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertTrue(result.hasErrors());
    }

    @Test
    void audienceValidator_rejectsTokenWithNoAudience() {
        var validator = new JwtDecoderConfig.AudienceValidator(BACKEND_CLIENT);
        Jwt jwt = token().claim("sub", "user").build();

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertTrue(result.hasErrors());
    }

    // --- Issuer -----------------------------------------------------------

    @Test
    void issuerValidator_acceptsEitherRealmUrlForTheSameRealm() {
        var validator = new JwtDecoderConfig.IssuerValidator(Set.of(PUBLIC_ISSUER, INTERNAL_ISSUER));

        assertFalse(validator.validate(token().issuer(PUBLIC_ISSUER).build()).hasErrors());
        assertFalse(validator.validate(token().issuer(INTERNAL_ISSUER).build()).hasErrors());
    }

    @Test
    void issuerValidator_rejectsForeignIssuer() {
        var validator = new JwtDecoderConfig.IssuerValidator(Set.of(PUBLIC_ISSUER));
        Jwt jwt = token().issuer("https://evil.example.com/realms/echno-realm").build();

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertTrue(result.hasErrors());
    }
}
