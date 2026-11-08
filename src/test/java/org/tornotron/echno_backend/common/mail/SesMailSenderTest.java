package org.tornotron.echno_backend.common.mail;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;
import software.amazon.awssdk.services.sesv2.model.TooManyRequestsException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SesMailSenderTest {

    private static final MailMessage MESSAGE = new MailMessage("person@example.test", "Hello", "<p>secret-link</p>",
            "secret-link");

    private final SesV2Client ses = mock(SesV2Client.class);

    @Test
    void sendsOneSimpleMessageWithBothPartsFromTheSender() {
        when(ses.sendEmail(any(SendEmailRequest.class))).thenReturn(SendEmailResponse.builder().messageId("m-1").build());
        SesMailSender sender = new SesMailSender(ses, "Echno <no-reply@example.test>", "support@example.test", "echno-set");

        assertThat(sender.send(MESSAGE)).isEqualTo("m-1");

        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(captor.capture());
        SendEmailRequest request = captor.getValue();
        assertThat(request.fromEmailAddress()).isEqualTo("Echno <no-reply@example.test>");
        assertThat(request.destination().toAddresses()).containsExactly("person@example.test");
        assertThat(request.replyToAddresses()).containsExactly("support@example.test");
        assertThat(request.configurationSetName()).isEqualTo("echno-set");
        assertThat(request.content().simple().subject().data()).isEqualTo("Hello");
        assertThat(request.content().simple().body().html().data()).isEqualTo("<p>secret-link</p>");
        assertThat(request.content().simple().body().text().data()).isEqualTo("secret-link");
        assertThat(request.content().simple().body().html().charset()).isEqualTo("UTF-8");
    }

    @Test
    void blankOptionalSettingsAreLeftOut() {
        when(ses.sendEmail(any(SendEmailRequest.class))).thenReturn(SendEmailResponse.builder().messageId("m-1").build());
        new SesMailSender(ses, "no-reply@example.test", " ", "").send(MESSAGE);

        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(captor.capture());
        assertThat(captor.getValue().hasReplyToAddresses()).isFalse();
        assertThat(captor.getValue().configurationSetName()).isNull();
    }

    @Test
    void aRejectedMessageIsPermanentAndNamesOnlyTheCode() {
        when(ses.sendEmail(any(SendEmailRequest.class))).thenThrow(MessageRejectedException.builder()
                .statusCode(400)
                .message("Email address is not verified. secret-link")
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("MessageRejected").build())
                .build());
        SesMailSender sender = new SesMailSender(ses, "no-reply@example.test", null, null);

        assertThatThrownBy(() -> sender.send(MESSAGE))
                .isInstanceOfSatisfying(MailSendException.class, e -> {
                    assertThat(e.permanent()).isTrue();
                    assertThat(e.getMessage()).contains("MessageRejected").doesNotContain("secret-link");
                });
    }

    @Test
    void throttlingAndServerErrorsAreWorthTryingAgain() {
        SesMailSender sender = new SesMailSender(ses, "no-reply@example.test", null, null);
        when(ses.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(TooManyRequestsException.builder().statusCode(429).build())
                .thenThrow(SesV2Exception.builder().statusCode(503).build())
                .thenThrow(SdkClientException.create("connection reset"));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> sender.send(MESSAGE))
                    .isInstanceOfSatisfying(MailSendException.class, e -> assertThat(e.permanent()).isFalse());
        }
    }
}
