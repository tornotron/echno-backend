package org.tornotron.echno_backend.common.mail;

import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * For a local run: records that a message would have gone, and to whom, and sends nothing. The bodies
 * are never logged, because a sign-in email carries a bearer link or code; only their sizes are.
 */
@Slf4j
public class LogMailSender implements MailSender {

    @Override
    public String send(MailMessage message) {
        String id = "log-" + UUID.randomUUID();
        log.info("Mail not sent (echno.mail.provider=log): {} to {}, subject \"{}\", html {} chars, text {} chars",
                id, message.to(), message.subject(), message.html().length(), message.text().length());
        return id;
    }
}
