package org.tornotron.echno_backend.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Objects;

/**
 * How the backend trusts the auth provider, under {@code echno.auth}.
 *
 * <p>Nothing here names a provider. The backend is an OAuth2 resource server: it accepts a bearer
 * JWT that the configured key set signed, and everything after that (who the caller is in this
 * application, which organizations they belong to, what they may do there) comes from the
 * application's own database. Moving to another provider is therefore a change of these values and
 * of nothing in code, provided the new provider issues signed JWTs and publishes its keys.
 *
 * @param jwt                   which tokens are accepted.
 * @param claims                where the caller's email, username and display name are read from on
 *                              those tokens. The subject is always the standard {@code sub} claim.
 * @param platformAdminSubjects the token subjects granted {@code ROLE_platform-admin}, the
 *                              platform-staff role that edits the global plan and feature catalogue.
 */
@ConfigurationProperties(prefix = "echno.auth")
public record AuthProperties(Jwt jwt, Claims claims, List<String> platformAdminSubjects) {

    public AuthProperties {
        jwt = jwt == null ? new Jwt(null, null, null) : jwt;
        claims = claims == null ? new Claims(null, null, null) : claims;
        platformAdminSubjects = nonBlank(platformAdminSubjects);
    }

    /**
     * @param jwkSetUri where the provider publishes the keys its tokens are signed with.
     * @param issuers   the {@code iss} values accepted; empty accepts any issuer the key set signed
     *                  for, and is logged as a warning at startup.
     * @param audiences the {@code aud} values accepted, any one of which is enough; empty skips the
     *                  check, and is logged as a warning at startup.
     */
    public record Jwt(String jwkSetUri, List<String> issuers, List<String> audiences) {

        public Jwt {
            issuers = nonBlank(issuers);
            audiences = nonBlank(audiences);
        }
    }

    /**
     * Claim names, because providers disagree on them and some leave them off the access token
     * unless asked. A claim that is absent reads as null rather than failing the request; the one
     * place that needs a value, registration, says so when it is missing.
     */
    public record Claims(String email, String username, String name) {

        public Claims {
            email = email == null || email.isBlank() ? "email" : email;
            username = username == null || username.isBlank() ? "preferred_username" : username;
            name = name == null || name.isBlank() ? "name" : name;
        }
    }

    private static List<String> nonBlank(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }
}
