package org.tornotron.echno_backend.common.mail;

/**
 * A send that did not go. {@link #permanent()} says whether trying the same message again could
 * succeed: a rejected message or an unverified sender stays refused, while throttling or a network
 * failure may clear.
 *
 * <p>The message names the service's error code and never a body, so it is safe to store and to show
 * an administrator.
 */
public class MailSendException extends RuntimeException {

    private final boolean permanent;

    public MailSendException(String message, boolean permanent, Throwable cause) {
        super(message, cause);
        this.permanent = permanent;
    }

    public MailSendException(String message, boolean permanent) {
        super(message);
        this.permanent = permanent;
    }

    public boolean permanent() {
        return permanent;
    }
}
