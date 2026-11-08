package org.tornotron.echno_backend.common.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import java.util.Set;

/**
 * The mail sender {@code echno.mail.provider} names. Under {@code none}, the default, nothing here is
 * created, so the stagings and CI need no mail setting at all.
 *
 * <p>Under {@code ses} the credentials come from the AWS default chain, which on a production host
 * ends at the instance role, so no key is configured. {@code echno.mail.ses.profile} picks a named
 * profile for a local run against a real SES account.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MailSettings.class)
@Slf4j
public class MailConfiguration {

    static final Set<String> PROVIDERS = Set.of("none", "log", "ses");

    @Bean
    @ConditionalOnMailSender
    MailSender mailSender(MailSettings settings) {
        String provider = settings.providerName();
        if (!PROVIDERS.contains(provider)) {
            throw new IllegalStateException("echno.mail.provider must be one of " + PROVIDERS + ", not " + provider);
        }
        if (settings.from() == null || settings.from().isBlank()) {
            throw new IllegalStateException("echno.mail.from (ECHNO_MAIL_FROM) is required when echno.mail.provider is "
                    + provider);
        }
        if (provider.equals("log")) {
            log.info("Mail: log only, nothing is sent");
            return new LogMailSender();
        }
        MailSettings.Ses ses = settings.sesOrEmpty();
        if (ses.region() == null || ses.region().isBlank()) {
            throw new IllegalStateException("echno.mail.ses.region (ECHNO_MAIL_SES_REGION) is required under SES");
        }
        SesV2Client client = SesV2Client.builder()
                .region(Region.of(ses.region().trim()))
                .credentialsProvider(credentials(ses))
                .build();
        log.info("Mail: Amazon SES in {}, from {}", ses.region().trim(), settings.from().trim());
        return new SesMailSender(client, settings.from().trim(), settings.replyTo(), ses.configurationSet());
    }

    private static AwsCredentialsProvider credentials(MailSettings.Ses ses) {
        if (ses.profile() != null && !ses.profile().isBlank()) {
            return ProfileCredentialsProvider.builder().profileName(ses.profile().trim()).build();
        }
        return DefaultCredentialsProvider.builder().build();
    }
}
