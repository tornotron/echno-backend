package org.tornotron.echno_backend.common.identity;

/**
 * Optional extra: creating an account with a password, for a provider whose accounts do not have to
 * come from an invitation. Reach it through {@link IdentityProvider#capabilities()}.
 */
public interface SelfRegistration {

    /**
     * Creates an account.
     *
     * @throws org.tornotron.echno_backend.common.exception.DuplicateResourceException if the email
     *         already has an account.
     */
    ExternalUserId createAccount(String username, String email, String password);

    /** Removes an account just created, when the local write that follows it fails. Never throws. */
    void deleteAccount(ExternalUserId user);
}
