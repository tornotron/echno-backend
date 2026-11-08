package org.tornotron.echno_backend.common.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.tornotron.echno_backend.auth.AuthProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Builds the resource-server {@link JwtDecoder} from {@code echno.auth.jwt}, so any provider that
 * signs JWTs and publishes its keys can be used by configuration alone.
 *
 * <p>The signature is checked against the published key set and the expiry against the clock.
 * Two further checks are configured per deployment:
 *
 * <ul>
 *   <li><b>Issuer</b> - the {@code iss} claim must be one of {@code issuers}. A list, because a
 *       provider can stamp a canonical issuer that differs from the address the backend dials.</li>
 *   <li><b>Audience</b> - the {@code aud} claim must contain one of {@code audiences}, which is
 *       what refuses a token the same provider minted for a different application.</li>
 * </ul>
 *
 * <p>Either may be left empty where the provider does not set the claim, which is logged as a
 * warning rather than refused, because the key set already limits tokens to that provider.
 */
@Configuration
@Slf4j
public class JwtDecoderConfig {

    @Bean
    public JwtDecoder jwtDecoder(AuthProperties properties) {
        AuthProperties.Jwt jwt = properties.jwt();
        if (jwt.jwkSetUri() == null || jwt.jwkSetUri().isBlank()) {
            throw new IllegalStateException("echno.auth.jwt.jwk-set-uri is not set; the backend cannot verify any token");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwt.jwkSetUri()).build();

        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator());

        if (!jwt.audiences().isEmpty()) {
            validators.add(new AudienceValidator(Set.copyOf(jwt.audiences())));
            log.info("JWT audience validation enabled (accepted audiences: {})", jwt.audiences());
        } else {
            log.warn("JWT audience validation is DISABLED (echno.auth.jwt.audiences is empty)");
        }

        if (!jwt.issuers().isEmpty()) {
            validators.add(new IssuerValidator(Set.copyOf(jwt.issuers())));
            log.info("JWT issuer validation enabled (accepted issuers: {})", jwt.issuers());
        } else {
            log.warn("JWT issuer validation is DISABLED (echno.auth.jwt.issuers is empty)");
        }

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    /** Fails a token whose {@code aud} claim contains none of the accepted audiences. */
    static class AudienceValidator implements OAuth2TokenValidator<Jwt> {

        private final Set<String> acceptedAudiences;

        AudienceValidator(Set<String> acceptedAudiences) {
            this.acceptedAudiences = acceptedAudiences;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt token) {
            List<String> audience = token.getAudience();
            if (audience != null && audience.stream().anyMatch(acceptedAudiences::contains)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "The token is not issued for this application",
                    null));
        }
    }

    /** Fails a token whose {@code iss} claim is not one of the accepted issuers. */
    static class IssuerValidator implements OAuth2TokenValidator<Jwt> {

        private final Set<String> acceptedIssuers;

        IssuerValidator(Set<String> acceptedIssuers) {
            this.acceptedIssuers = acceptedIssuers;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt token) {
            String issuer = token.getIssuer() == null ? null : token.getIssuer().toString();
            if (issuer != null && acceptedIssuers.contains(issuer)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "The issuer '" + issuer + "' is not accepted",
                    null));
        }
    }
}
