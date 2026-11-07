package org.tornotron.echno_backend.common.identity.workos;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.tornotron.echno_backend.common.configuration.JwtDecoderConfig;
import org.tornotron.echno_backend.common.identity.ConditionalOnWorkOs;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The WorkOS adapter's token side: the decoder and the mapper.
 *
 * <p>A token is accepted only when all of these hold:
 * <ul>
 *   <li>it is signed with one of the Application's keys, fetched from {@code /sso/jwks/<client id>};</li>
 *   <li>it has not expired;</li>
 *   <li>its {@code aud} holds {@code echno.security.jwt.audience}. WorkOS writes no {@code aud} by
 *       default, so the environment's JWT template has to add it, for example
 *       {@code {"aud": "echno-api"}};</li>
 *   <li>its {@code iss} is one of {@code echno.security.jwt.accepted-issuers}, read from a decoded
 *       token and never copied from the AuthKit discovery document, whose issuer differs.</li>
 * </ul>
 * There is no switch that turns the audience or issuer check off under WorkOS.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWorkOs
@EnableConfigurationProperties(WorkOsSettings.class)
@Slf4j
public class WorkOsConfiguration {

    @Bean
    WorkOsTokenMapper workOsTokenMapper(WorkOsSettings settings) {
        return new WorkOsTokenMapper(settings);
    }

    @Bean
    JwtDecoder workOsJwtDecoder(WorkOsSettings settings,
                                @Value("${echno.security.jwt.audience:}") String audience,
                                @Value("${echno.security.jwt.accepted-issuers:}") List<String> acceptedIssuers) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(settings.jwksUri().toString())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(validator(audience, acceptedIssuers));
        log.info("WorkOS tokens: keys from {}, audience {}, issuers {}",
                settings.jwksUri(), audience, acceptedIssuers);
        return decoder;
    }

    /** Expiry, audience and issuer, all required. */
    static OAuth2TokenValidator<Jwt> validator(String audience, List<String> acceptedIssuers) {
        Set<String> issuers = acceptedIssuers == null ? Set.of()
                : acceptedIssuers.stream()
                        .filter(issuer -> issuer != null && !issuer.isBlank())
                        .map(String::trim)
                        .collect(Collectors.toSet());
        if (audience == null || audience.isBlank() || issuers.isEmpty()) {
            throw new IllegalStateException("A WorkOS token needs a required audience and at least one accepted issuer");
        }
        return new DelegatingOAuth2TokenValidator<>(List.of(
                new JwtTimestampValidator(),
                new JwtDecoderConfig.AudienceValidator(audience.trim()),
                new JwtDecoderConfig.IssuerValidator(issuers)));
    }
}
