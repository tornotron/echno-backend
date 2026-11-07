package org.tornotron.echno_backend.common.identity.workos;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.tornotron.echno_backend.common.identity.Principal;
import org.tornotron.echno_backend.common.identity.SignInMethod;

import java.net.URI;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tokens shaped like WorkOS's, signed with a key set held here, through the same validator the
 * adapter's decoder uses. Only the key source differs from production, which fetches the set.
 */
class WorkOsTokenTest {

    private static final String CLIENT_ID = "client_test_0001";
    private static final WorkOsSettings SETTINGS =
            new WorkOsSettings("unused", CLIENT_ID, URI.create("https://api.workos.example"));
    private static final String ISSUER = "https://api.workos.example/user_management/" + CLIENT_ID;
    private static final String CUSTOM_DOMAIN_ISSUER = "https://auth.example.test/user_management/" + CLIENT_ID;
    private static final String AUDIENCE = "echno-api";

    private static RSAKey ourKey;
    private static RSAKey anotherClientsKey;
    private static NimbusJwtDecoder decoder;

    @BeforeAll
    static void keys() throws Exception {
        ourKey = new RSAKeyGenerator(2048).keyID("ours").generate();
        anotherClientsKey = new RSAKeyGenerator(2048).keyID("theirs").generate();
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        // As Spring's own builder does: claims are left to the validator under test.
        processor.setJWTClaimsSetVerifier((claims, context) -> { });
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256,
                new ImmutableJWKSet<>(new JWKSet(ourKey.toPublicJWK()))));
        decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(WorkOsConfiguration.validator(AUDIENCE, List.of(ISSUER, CUSTOM_DOMAIN_ISSUER)));
    }

    private static JWTClaimsSet.Builder claims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("user_01TEST")
                .audience(AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("sid", "session_01TEST")
                .claim("client_id", CLIENT_ID);
    }

    private static String sign(JWTClaimsSet claims, RSAKey key) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(),
                claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Test
    void aGoodTokenIsAcceptedAndBecomesTheSubject() throws Exception {
        Jwt jwt = decoder.decode(sign(claims().build(), ourKey));

        Principal principal = new WorkOsTokenMapper(SETTINGS).toPrincipal(jwt);

        assertThat(principal.subject()).isEqualTo("user_01TEST");
        assertThat(principal.issuer()).isEqualTo(ISSUER);
        assertThat(principal.authentication()).isEqualTo(SignInMethod.UNKNOWN);
    }

    @Test
    void aTokenWithNoOrganizationIsAccepted() throws Exception {
        assertThat(decoder.decode(sign(claims().build(), ourKey)).hasClaim("org_id")).isFalse();
    }

    @Test
    void aWrongIssuerIsRefused() {
        assertThatThrownBy(() -> decoder.decode(sign(
                claims().issuer("https://api.workos.example/user_management/client_other").build(), ourKey)))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("issuer");
    }

    @Test
    void aMissingAudienceIsRefused() {
        assertThatThrownBy(() -> decoder.decode(sign(claims().audience((String) null).build(), ourKey)))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("audience");
    }

    @Test
    void anotherAudienceIsRefused() {
        assertThatThrownBy(() -> decoder.decode(sign(claims().audience("another-api").build(), ourKey)))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("audience");
    }

    @Test
    void anExpiredTokenIsRefused() {
        Instant past = Instant.now().minusSeconds(3600);
        assertThatThrownBy(() -> decoder.decode(sign(claims()
                .issueTime(Date.from(past))
                .expirationTime(Date.from(past.plusSeconds(300)))
                .build(), ourKey)))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void aTokenSignedWithAnotherClientsKeyIsRefused() {
        assertThatThrownBy(() -> decoder.decode(sign(claims().build(), anotherClientsKey)))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void theProvidersOwnOrganizationRoleAndPermissionsAreIgnored() throws Exception {
        Jwt jwt = decoder.decode(sign(claims()
                .claim("org_id", "org_01OTHER")
                .claim("role", "admin")
                .claim("roles", List.of("admin"))
                .claim("permissions", List.of("everything:all"))
                .claim("email", "someone@example.org")
                .build(), ourKey));

        Principal principal = new WorkOsTokenMapper(SETTINGS).toPrincipal(jwt);

        assertThat(principal).isEqualTo(new Principal(ISSUER, "user_01TEST", null, SignInMethod.UNKNOWN));
    }

    @Test
    void aCustomDomainIssuerMapsToTheSameIssuerAsTheDefaultOne() throws Exception {
        Jwt jwt = decoder.decode(sign(claims().issuer(CUSTOM_DOMAIN_ISSUER).build(), ourKey));

        assertThat(new WorkOsTokenMapper(SETTINGS).toPrincipal(jwt).issuer()).isEqualTo(ISSUER);
    }

    @Test
    void noAudienceOrNoIssuerCannotBuildAValidator() {
        assertThatThrownBy(() -> WorkOsConfiguration.validator("", List.of(ISSUER)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WorkOsConfiguration.validator(AUDIENCE, List.of(" ")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theSettingsDeriveTheKeyAddressFromTheClientId() {
        assertThat(SETTINGS.jwksUri()).hasToString("https://api.workos.example/sso/jwks/" + CLIENT_ID);
        assertThat(SETTINGS.toString()).doesNotContain("unused");
    }
}
