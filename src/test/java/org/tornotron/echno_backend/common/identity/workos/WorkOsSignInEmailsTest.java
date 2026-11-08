package org.tornotron.echno_backend.common.identity.workos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.tornotron.echno_backend.common.identity.EchnoOrgId;
import org.tornotron.echno_backend.common.identity.ExternalInvitationId;
import org.tornotron.echno_backend.common.identity.ExternalUserId;
import org.tornotron.echno_backend.common.identity.SignInEmails;
import org.tornotron.echno_backend.common.identity.SignInEmails.Kind;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** The sign-in email extra against the stub: requests from the Events API, secrets from the Get calls. */
@ExtendWith(OutputCaptureExtension.class)
class WorkOsSignInEmailsTest {

    private static final EchnoOrgId ORGANIZATION = new EchnoOrgId(52L);
    private static final Instant LONG_AGO = Instant.parse("2020-01-01T00:00:00Z");

    private final WorkOsStub stub = new WorkOsStub();
    private final WorkOsApi api = WorkOsApi.create(stub.settings(),
            new RetryAfterInterceptor(3, Duration.ofSeconds(10), d -> { }));
    private final WorkOsIdentityProvider provider = new WorkOsIdentityProvider(api, Clock.systemUTC(), d -> { });
    private final SignInEmails emails = provider.capabilities().signInEmails().orElseThrow();

    @AfterEach
    void stop() {
        stub.close();
    }

    @Test
    void anInvitationAndItsResendAreTwoRequestsForTheSameInvitation() {
        provider.createOrganization(ORGANIZATION, "Example Organization");
        ExternalInvitationId invitation = provider.invite("new.person@example.test", ORGANIZATION, null, Duration.ofDays(7));
        provider.resendInvitation(invitation);

        SignInEmails.Page page = emails.requestsAfter(null, LONG_AGO, 10);

        assertThat(page.requests()).extracting(SignInEmails.Request::kind).containsExactly(Kind.INVITATION, Kind.INVITATION);
        assertThat(page.requests()).extracting(SignInEmails.Request::objectId)
                .containsOnly(invitation.value());
        assertThat(page.requests()).extracting(SignInEmails.Request::id).doesNotHaveDuplicates();
        assertThat(page.cursor()).isEqualTo(page.requests().get(1).id());
    }

    @Test
    void readingOnFromTheCursorReturnsOnlyWhatCameAfter() {
        String user = stub.addUser("someone@example.test", true, "Some", "One");
        provider.sendPasswordReset(new ExternalUserId(user));
        SignInEmails.Page first = emails.requestsAfter(null, LONG_AGO, 10);

        stub.addEmailVerification(user);
        SignInEmails.Page second = emails.requestsAfter(first.cursor(), LONG_AGO, 10);
        SignInEmails.Page third = emails.requestsAfter(second.cursor(), LONG_AGO, 10);

        assertThat(first.requests()).extracting(SignInEmails.Request::kind).containsExactly(Kind.PASSWORD_RESET);
        assertThat(second.requests()).extracting(SignInEmails.Request::kind).containsExactly(Kind.EMAIL_VERIFICATION);
        assertThat(third.requests()).isEmpty();
        assertThat(third.cursor()).isEqualTo(second.cursor());
    }

    @Test
    void withNoCursorOnlyRequestsSinceTheStartAreRead() {
        String user = stub.addUser("someone@example.test", true, "Some", "One");
        provider.sendPasswordReset(new ExternalUserId(user));

        SignInEmails.Page page = emails.requestsAfter(null, Instant.now().plusSeconds(60), 10);

        assertThat(page.requests()).isEmpty();
        assertThat(page.cursor()).isNull();
    }

    @Test
    void eventsOfOtherTypesAreNotAskedFor() {
        stub.addUnrelatedEvent();

        assertThat(emails.requestsAfter(null, LONG_AGO, 10).requests()).isEmpty();
        assertThat(stub.requests()).anyMatch(request -> request.startsWith("GET /events"));
    }

    @Test
    void eachKindReadsItsSecretAndAddress() {
        provider.createOrganization(ORGANIZATION, "Example Organization");
        String inviter = stub.addUser("inviter@example.test", true, "In", "Viter");
        ExternalInvitationId invitation = provider.invite("new.person@example.test", ORGANIZATION,
                new ExternalUserId(inviter), Duration.ofDays(7));
        String user = stub.addUser("someone@example.test", true, "Some", "One");
        provider.sendPasswordReset(new ExternalUserId(user));
        String verification = stub.addEmailVerification(user);
        String reset = emails.requestsAfter(null, LONG_AGO, 10).requests().stream()
                .filter(r -> r.kind() == Kind.PASSWORD_RESET).findFirst().orElseThrow().objectId();

        SignInEmails.Link invited = emails.link(Kind.INVITATION, invitation.value()).orElseThrow();
        SignInEmails.Link resetLink = emails.link(Kind.PASSWORD_RESET, reset).orElseThrow();
        SignInEmails.Link code = emails.link(Kind.EMAIL_VERIFICATION, verification).orElseThrow();

        assertThat(invited.email()).isEqualTo("new.person@example.test");
        assertThat(invited.secret()).isEqualTo(WorkOsStub.BEARER_SENTINEL);
        assertThat(invited.open()).isTrue();
        assertThat(invited.organizationId()).isNotBlank();
        assertThat(invited.inviter()).isEqualTo(new ExternalUserId(inviter));
        assertThat(invited.expiresAt()).isAfter(Instant.now());
        assertThat(resetLink.email()).isEqualTo("someone@example.test");
        assertThat(resetLink.secret()).isEqualTo(WorkOsStub.BEARER_SENTINEL);
        assertThat(code.secret()).isEqualTo(WorkOsStub.BEARER_SENTINEL);
        assertThat(code.expiresAt()).isAfter(Instant.now());
    }

    @Test
    void aRevokedInvitationIsNoLongerOpen() {
        provider.createOrganization(ORGANIZATION, "Example Organization");
        ExternalInvitationId invitation = provider.invite("new.person@example.test", ORGANIZATION, null, Duration.ofDays(7));
        provider.revokeInvitation(invitation);

        assertThat(emails.link(Kind.INVITATION, invitation.value())).get()
                .extracting(SignInEmails.Link::open).isEqualTo(false);
    }

    @Test
    void somethingWorkOsDoesNotHaveIsEmpty() {
        assertThat(emails.link(Kind.PASSWORD_RESET, "password_reset_01NOSUCHTHING")).isEmpty();
        assertThat(emails.link(Kind.EMAIL_VERIFICATION, "email_verification_01NOSUCHTHING")).isEmpty();
        assertThat(emails.link(Kind.INVITATION, "invitation_01NOSUCHTHING")).isEmpty();
    }

    @Test
    void theSecretIsNeitherPrintedNorLogged(CapturedOutput output) {
        String user = stub.addUser("someone@example.test", true, "Some", "One");
        String verification = stub.addEmailVerification(user);

        SignInEmails.Link link = emails.link(Kind.EMAIL_VERIFICATION, verification).orElseThrow();

        assertThat(link.toString()).doesNotContain(WorkOsStub.BEARER_SENTINEL);
        assertThat(api.emailVerification(verification).orElseThrow().toString()).doesNotContain(WorkOsStub.BEARER_SENTINEL);
        assertThat(output.getAll()).doesNotContain(WorkOsStub.BEARER_SENTINEL);
    }
}
