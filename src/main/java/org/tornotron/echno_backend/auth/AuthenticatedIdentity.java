package org.tornotron.echno_backend.auth;

import java.util.Map;

/**
 * Who the auth provider says the caller is, in terms that do not depend on which provider said it.
 *
 * <p>This is identity only. It carries nothing about organizations or roles, because those belong
 * to the application and are looked up from its database by the {@code AuthorityProvider}s.
 *
 * @param subject  the provider's stable id for the account, the {@code sub} claim. It is what
 *                 {@code users_table.auth_subject} binds a user row to. Never null.
 * @param email    the account's email, or null when the token does not carry one.
 * @param username a login name, or null when the token does not carry one.
 * @param name     a display name, or null when the token does not carry one.
 * @param claims   every claim on the token, for an {@code AuthorityProvider} that reads something
 *                 provider-specific. Read-only.
 */
public record AuthenticatedIdentity(String subject, String email, String username, String name,
                                    Map<String, Object> claims) {

    public AuthenticatedIdentity {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("An authenticated identity needs a subject");
        }
        claims = claims == null ? Map.of() : Map.copyOf(claims);
    }
}
