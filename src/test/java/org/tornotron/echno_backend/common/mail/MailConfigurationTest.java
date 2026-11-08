package org.tornotron.echno_backend.common.mail;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class MailConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(MailConfiguration.class);

    @Test
    void noProviderMeansNoSender() {
        context.run(started -> assertThat(started).hasNotFailed().doesNotHaveBean(MailSender.class));
    }

    @Test
    void noneMeansNoSender() {
        context.withPropertyValues("echno.mail.provider=none")
                .run(started -> assertThat(started).hasNotFailed().doesNotHaveBean(MailSender.class));
    }

    @Test
    void logGivesTheLogSender() {
        context.withPropertyValues("echno.mail.provider=log", "echno.mail.from=no-reply@example.test")
                .run(started -> assertThat(started).getBean(MailSender.class).isInstanceOf(LogMailSender.class));
    }

    @Test
    void sesGivesTheSesSender() {
        context.withPropertyValues("echno.mail.provider=ses", "echno.mail.from=no-reply@example.test",
                        "echno.mail.ses.region=us-east-1")
                .run(started -> assertThat(started).getBean(MailSender.class).isInstanceOf(SesMailSender.class));
    }

    @Test
    void aSenderNeedsAFromAddress() {
        context.withPropertyValues("echno.mail.provider=log")
                .run(started -> assertThat(started).hasFailed().getFailure()
                        .hasRootCauseMessage("echno.mail.from (ECHNO_MAIL_FROM) is required when echno.mail.provider is log"));
    }

    @Test
    void sesNeedsARegion() {
        context.withPropertyValues("echno.mail.provider=ses", "echno.mail.from=no-reply@example.test")
                .run(started -> assertThat(started).hasFailed().getFailure()
                        .hasRootCauseMessage("echno.mail.ses.region (ECHNO_MAIL_SES_REGION) is required under SES"));
    }

    @Test
    void anUnknownProviderIsRefused() {
        context.withPropertyValues("echno.mail.provider=smtp", "echno.mail.from=no-reply@example.test")
                .run(started -> assertThat(started).hasFailed());
    }
}
