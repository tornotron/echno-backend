package org.tornotron.echno_backend.common.mail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class LogMailSenderTest {

    @Test
    void logsTheRecipientAndSubjectAndNeverTheBody(CapturedOutput output) {
        String id = new LogMailSender().send(new MailMessage("person@example.test", "Reset your password",
                "<a href=\"https://example.test/reset?token=SECRET-TOKEN\">Reset</a>",
                "https://example.test/reset?token=SECRET-TOKEN"));

        assertThat(id).startsWith("log-");
        assertThat(output.getAll()).contains("person@example.test").contains("Reset your password")
                .doesNotContain("SECRET-TOKEN");
    }

    @Test
    void aMessagePrintsNoBody() {
        MailMessage message = new MailMessage("person@example.test", "Line one\r\nline two", "<p>SECRET</p>", "SECRET");

        assertThat(message.subject()).isEqualTo("Line one line two");
        assertThat(message.toString()).doesNotContain("SECRET");
    }
}
