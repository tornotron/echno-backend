package org.tornotron.echno_backend.common.mail;

import java.util.Objects;

/**
 * One email to one person, with an HTML body and a plain text body carrying the same content.
 *
 * <p>A body can hold a bearer value (a sign-in link or a verification code), so {@link #toString()}
 * names only the recipient and the subject, and nothing that prints a message prints a body.
 *
 * @param to      the recipient's address.
 * @param subject one line, with any line break removed.
 * @param html    the HTML body.
 * @param text    the plain text body.
 */
public record MailMessage(String to, String subject, String html, String text) {

    public MailMessage {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(html, "html");
        Objects.requireNonNull(text, "text");
        subject = Objects.requireNonNull(subject, "subject").replaceAll("[\\r\\n]+", " ").strip();
    }

    @Override
    public String toString() {
        return "MailMessage[to=" + to + ", subject=" + subject + "]";
    }
}
