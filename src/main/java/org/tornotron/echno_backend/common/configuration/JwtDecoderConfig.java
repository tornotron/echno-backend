package org.tornotron.echno_backend.common.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the resource-server {@link JwtDecoder} so it does more than the default
 * signature + expiry check. Spring's auto-configured decoder (built from
 * {@code jwk-set-uri} alone) accepts any token the realm signed, regardless of who
 * it was minted for; this adds:
 *
 * <ul>
 *   <li><b>Audience</b> - the token's {@code aud} must contain the backend client id.
 *       Every request's token is first exchanged for an RPT scoped to exactly this
 *       client (see {@link RPTExchangeFilter}), so legitimate tokens always match while
 *       a token minted for a different client in the same realm is rejected.</li>
 *   <li><b>Issuer</b> - optional. When {@code echno.security.jwt.accepted-issuers} is
 *       set, the {@code iss} claim must be one of the listed values. Keycloak stamps the
 *       realm's canonical (frontend) issuer, which can differ from the internal URL the
 *       backend dials, so the property takes a list.</li>
 * </ul>
 */
@Configuration
@Slf4j
public class JwtDecoderConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwkSetUri;

    @Value("${jwt.auth.converter.resource-id}")
    private String resourceId;

    @Value("${echno.security.jwt.require-audience:true}")
    private boolean requireAudience;

    @Value("${echno.security.jwt.accepted-issuers:}")
    private List<String> acceptedIssuers;

    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();

        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator());

        if (requireAudience) {
            validators.add(new AudienceValidator(resourceId));
            log.info("JWT audience validation enabled (required audience: {})", resourceId);
        } else {
            log.warn("JWT audience validation is DISABLED");
        }

        Set<String> issuers = acceptedIssuers == null ? Set.of()
                : acceptedIssuers.stream()
                        .filter(s -> s != null && !s.isBlank())
                        .map(String::trim)
                        .collect(Collectors.toSet());
        if (!issuers.isEmpty()) {
            validators.add(new IssuerValidator(issuers));
            log.info("JWT issuer validation enabled (accepted issuers: {})", issuers);
        } else {
            log.warn("JWT issuer validation is DISABLED (echno.security.jwt.accepted-issuers is empty)");
        }

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    /** Fails a token whose {@code aud} claim does not contain the required audience. */
    static class AudienceValidator implements OAuth2TokenValidator<Jwt> {

        private final String requiredAudience;

        AudienceValidator(String requiredAudience) {
            this.requiredAudience = requiredAudience;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt token) {
            if (token.getAudience() != null && token.getAudience().contains(requiredAudience)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "The required audience '" + requiredAudience + "' is missing",
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
