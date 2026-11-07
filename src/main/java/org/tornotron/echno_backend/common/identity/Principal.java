package org.tornotron.echno_backend.common.identity;

import org.springframework.lang.Nullable;

/**
 * The person a validated token speaks for. A token is matched by the issuer that signed it, so the
 * issuer and subject together identify them.
 *
 * <p>{@code email} is optional, because some providers put none in the token. There is deliberately
 * no organization here: the active organization comes from the request and is checked against
 * Echno's own records.
 */
public record Principal(String issuer, String subject, @Nullable String email, SignInMethod authentication) {

    public Principal {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("A principal needs an issuer");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("A principal needs a subject");
        }
        if (authentication == null) {
            authentication = SignInMethod.UNKNOWN;
        }
    }
}
