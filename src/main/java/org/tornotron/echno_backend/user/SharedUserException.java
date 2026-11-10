package org.tornotron.echno_backend.user;

/**
 * Refuses a change an organization's administrator asked for on a user who also belongs to another
 * organization, where the change would reach past this one: deleting the shared account, or
 * rewriting what the person signs in with. Answered with 409.
 */
public class SharedUserException extends RuntimeException {

    public SharedUserException(String message) {
        super(message);
    }
}
