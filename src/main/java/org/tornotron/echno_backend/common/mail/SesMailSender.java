package org.tornotron.echno_backend.common.mail;

import org.springframework.lang.Nullable;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.MailFromDomainNotVerifiedException;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

import java.nio.charset.StandardCharsets;

/**
 * Sends through the Amazon SES v2 {@code SendEmail} call, as a simple message with an HTML part and a
 * text part, so the instance role needs only {@code ses:SendEmail} on the sender's identity.
 *
 * <p>A refusal that the same message cannot get past (rejected, an unverified sender or recipient, a
 * malformed request) is permanent. Throttling, a paused account, a server error or a network failure
 * may clear, so the caller tries again. Only the error code reaches the exception, never a body.
 */
public class SesMailSender implements MailSender {

    private static final String CHARSET = StandardCharsets.UTF_8.name();

    private final SesV2Client ses;
    private final String from;
    @Nullable
    private final String replyTo;
    @Nullable
    private final String configurationSet;

    public SesMailSender(SesV2Client ses, String from, @Nullable String replyTo, @Nullable String configurationSet) {
        this.ses = ses;
        this.from = from;
        this.replyTo = blankToNull(replyTo);
        this.configurationSet = blankToNull(configurationSet);
    }

    @Override
    public String send(MailMessage message) {
        SendEmailRequest.Builder request = SendEmailRequest.builder()
                .fromEmailAddress(from)
                .destination(Destination.builder().toAddresses(message.to()).build())
                .content(EmailContent.builder().simple(Message.builder()
                        .subject(content(message.subject()))
                        .body(Body.builder().html(content(message.html())).text(content(message.text())).build())
                        .build()).build());
        if (replyTo != null) {
            request.replyToAddresses(replyTo);
        }
        if (configurationSet != null) {
            request.configurationSetName(configurationSet);
        }
        try {
            return ses.sendEmail(request.build()).messageId();
        } catch (MessageRejectedException | MailFromDomainNotVerifiedException | BadRequestException
                 | NotFoundException refused) {
            throw new MailSendException("SES refused the message: " + code(refused), true, refused);
        } catch (SesV2Exception failed) {
            int status = failed.statusCode();
            boolean permanent = status >= 400 && status < 500 && status != 429 && !failed.isThrottlingException();
            throw new MailSendException("SES did not take the message: " + code(failed), permanent, failed);
        } catch (SdkClientException unreachable) {
            throw new MailSendException("SES could not be reached: " + unreachable.getClass().getSimpleName(),
                    false, unreachable);
        }
    }

    private static Content content(String data) {
        return Content.builder().data(data).charset(CHARSET).build();
    }

    private static String code(SesV2Exception e) {
        String code = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
        return (code == null ? e.getClass().getSimpleName() : code) + " (HTTP " + e.statusCode() + ")";
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
