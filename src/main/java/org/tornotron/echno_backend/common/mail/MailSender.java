package org.tornotron.echno_backend.common.mail;

/**
 * Sends one email. {@code echno.mail.provider} picks the adapter: {@code ses} for Amazon SES,
 * {@code log} for a local run, and {@code none}, the default, for no sender at all, in which case no
 * bean of this type exists and the identity provider keeps sending its own email.
 *
 * <p>A sender makes one attempt. Retrying, and deciding when to stop, belongs to the caller.
 */
public interface MailSender {

    /**
     * Hands the message to the mail service.
     *
     * @return the service's id for the message, for tracing a delivery problem.
     * @throws MailSendException if the service refused or could not be reached.
     */
    String send(MailMessage message);
}
