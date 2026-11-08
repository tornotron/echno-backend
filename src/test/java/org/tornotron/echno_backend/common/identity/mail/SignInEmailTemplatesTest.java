package org.tornotron.echno_backend.common.identity.mail;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.identity.SignInEmails.Kind;
import org.tornotron.echno_backend.common.mail.MailMessage;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SignInEmailTemplatesTest {

    private static final String LINK = "https://example.test/invite?invitation_token=abc&x=1";
    private static final Instant EXPIRES = Instant.parse("2026-11-15T09:30:00Z");

    private final SignInEmailTemplates templates = new SignInEmailTemplates("Echno");

    @Test
    void anInvitationNamesTheOrganizationAndInviterAndCarriesTheLink() {
        MailMessage message = templates.render(Kind.INVITATION, new SignInEmailTemplates.Values(
                "person@example.test", LINK, EXPIRES, "Example Builders", "Asha Rao"));

        assertThat(message.to()).isEqualTo("person@example.test");
        assertThat(message.subject()).isEqualTo("Join Example Builders on Echno");
        assertThat(message.html()).contains("Asha Rao has invited you to join Example Builders on Echno.")
                .contains("href=\"https://example.test/invite?invitation_token=abc&amp;x=1\"")
                .contains("15 November 2026, 09:30 UTC");
        assertThat(message.text()).contains("Asha Rao has invited you to join Example Builders on Echno.")
                .contains(LINK).contains("15 November 2026, 09:30 UTC");
    }

    @Test
    void anInvitationWithNothingKnownStillReads() {
        MailMessage message = templates.render(Kind.INVITATION, new SignInEmailTemplates.Values(
                "person@example.test", LINK, null, null, null));

        assertThat(message.subject()).isEqualTo("You are invited to Echno");
        assertThat(message.text()).contains("You have been invited to Echno.").contains(LINK).doesNotContain("null");
        assertThat(message.html()).doesNotContain("expires");
    }

    @Test
    void namesTypedByUsersAreEscapedInTheHtml() {
        MailMessage message = templates.render(Kind.INVITATION, new SignInEmailTemplates.Values(
                "person@example.test", LINK, EXPIRES, "<script>alert(1)</script>", "A & B"));

        assertThat(message.html()).doesNotContain("<script>").contains("&lt;script&gt;").contains("A &amp; B");
    }

    @Test
    void aPasswordResetCarriesItsLink() {
        MailMessage message = templates.render(Kind.PASSWORD_RESET, new SignInEmailTemplates.Values(
                "person@example.test", "https://example.test/reset?token=t", EXPIRES, null, null));

        assertThat(message.subject()).isEqualTo("Reset your Echno password");
        assertThat(message.html()).contains("href=\"https://example.test/reset?token=t\"");
        assertThat(message.text()).contains("https://example.test/reset?token=t");
    }

    @Test
    void aVerificationCarriesTheCodeAndNoLink() {
        MailMessage message = templates.render(Kind.EMAIL_VERIFICATION, new SignInEmailTemplates.Values(
                "person@example.test", "123456", EXPIRES, null, null));

        assertThat(message.subject()).isEqualTo("Your Echno verification code");
        assertThat(message.html()).contains("123456").doesNotContain("href=");
        assertThat(message.text()).contains("123456");
    }

    @Test
    void valuesPrintNoSecret() {
        assertThat(new SignInEmailTemplates.Values("person@example.test", LINK, EXPIRES, null, null).toString())
                .doesNotContain("invitation_token");
    }
}
