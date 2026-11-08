package org.tornotron.echno_backend.common.identity.mail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.identity.IdentityCapabilities;
import org.tornotron.echno_backend.common.identity.IdentityProvider;
import org.tornotron.echno_backend.common.identity.IdentityTokenMapper;
import org.tornotron.echno_backend.common.identity.SignInEmails;
import org.tornotron.echno_backend.common.identity.SignInEmails.Kind;
import org.tornotron.echno_backend.common.mail.FakeMailSender;
import org.tornotron.echno_backend.common.mail.MailSendException;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The sign-in email outbox on CockroachDB, against a provider feed and a mail sender held in memory. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SignInEmailOutboxIT extends AbstractIntegrationTest {

    private static final String LINK = "https://example.test/accept?invitation_token=SECRET-TOKEN";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    private final Feed feed = new Feed();
    private final FakeMailSender mail = new FakeMailSender();
    private final IdentityProvider identity = mock(IdentityProvider.class);
    private final IdentityTokenMapper mapper = mock(IdentityTokenMapper.class);
    private SignInEmailOutbox outbox;

    @BeforeEach
    void anEmptyOutbox() {
        clean();
        when(identity.capabilities()).thenReturn(
                new IdentityCapabilities(Optional.empty(), Optional.empty(), Optional.of(feed)));
        when(mapper.provider()).thenReturn("workos");
        when(mapper.issuer()).thenReturn("https://issuer.example.test");
        outbox = outbox();
    }

    @AfterEach
    void leaveItEmpty() {
        clean();
    }

    @Test
    void aRequestIsSentOnceWithItsLink() {
        feed.request("event_1", Kind.INVITATION, "invitation_1", feed.link("new.person@example.test", LINK));

        assertThat(outbox.readRequests()).isEqualTo(1);
        outbox.sendDue();
        outbox.readRequests();
        outbox.sendDue();

        assertThat(mail.sent()).singleElement().satisfies(message -> {
            assertThat(message.to()).isEqualTo("new.person@example.test");
            assertThat(message.text()).contains(LINK);
        });
        assertThat(status("event_1")).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT message_id FROM sign_in_email WHERE request_id = 'event_1'", String.class))
                .isEqualTo("fake-1");
    }

    @Test
    void aRequestReadAgainIsNotSentAgain() {
        feed.request("event_1", Kind.PASSWORD_RESET, "reset_1", feed.link("someone@example.test", LINK));
        outbox.readRequests();
        jdbc.update("UPDATE sign_in_email_cursor SET last_request_id = NULL");

        outbox.readRequests();
        outbox.sendDue();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM sign_in_email", Integer.class)).isEqualTo(1);
        assertThat(mail.sent()).hasSize(1);
    }

    @Test
    void aSecondReplicaReadsOnFromWhereTheFirstStopped() {
        feed.request("event_1", Kind.INVITATION, "invitation_1", feed.link("a@example.test", LINK));
        feed.request("event_2", Kind.INVITATION, "invitation_2", feed.link("b@example.test", LINK));
        SignInEmailOutbox other = outbox();

        outbox.readRequests();
        other.readRequests();
        outbox.sendDue();
        other.sendDue();

        assertThat(mail.sent()).extracting(m -> m.to()).containsExactlyInAnyOrder("a@example.test", "b@example.test");
    }

    @Test
    void aRequestFromBeforeTheFirstPassLeadIsNotSent() {
        feed.request("event_old", Kind.INVITATION, "invitation_old", feed.link("old@example.test", LINK),
                Instant.now().minus(Duration.ofHours(2)));
        feed.request("event_recent", Kind.INVITATION, "invitation_recent", feed.link("recent@example.test", LINK),
                Instant.now().minus(Duration.ofMinutes(5)));

        outbox.readRequests();
        outbox.sendDue();

        assertThat(mail.sent()).extracting(m -> m.to()).containsExactly("recent@example.test");
    }

    @Test
    void aFailureIsTriedAgainAndThenSent() {
        feed.request("event_1", Kind.INVITATION, "invitation_1", feed.link("new.person@example.test", LINK));
        mail.failNext(new MailSendException("SES did not take the message: Throttling (HTTP 429)", false));
        outbox.readRequests();

        outbox.sendDue();
        assertThat(status("event_1")).isEqualTo("PENDING");
        assertThat(error("event_1")).contains("Throttling");
        makeDue();
        outbox.sendDue();

        assertThat(status("event_1")).isEqualTo("SENT");
        assertThat(error("event_1")).isNull();
        assertThat(mail.attempts()).isEqualTo(2);
    }

    @Test
    void aPermanentRefusalFailsAtOnce() {
        feed.request("event_1", Kind.INVITATION, "invitation_1", feed.link("new.person@example.test", LINK));
        mail.failNext(new MailSendException("SES refused the message: MessageRejected (HTTP 400)", true));
        outbox.readRequests();

        outbox.sendDue();

        assertThat(status("event_1")).isEqualTo("FAILED");
        assertThat(error("event_1")).isEqualTo("SES refused the message: MessageRejected (HTTP 400)");
    }

    @Test
    void tooManyFailuresGiveUp() {
        feed.request("event_1", Kind.INVITATION, "invitation_1", feed.link("new.person@example.test", LINK));
        outbox.readRequests();
        for (int i = 0; i < SignInEmailOutbox.MOST_ATTEMPTS; i++) {
            mail.failNext(new MailSendException("SES could not be reached: SdkClientException", false));
            makeDue();
            outbox.sendDue();
        }

        assertThat(status("event_1")).isEqualTo("FAILED");
        assertThat(mail.sent()).isEmpty();
    }

    @Test
    void aLinkThatExpiredOrWasWithdrawnOrIsGoneIsSkipped() {
        feed.request("event_1", Kind.INVITATION, "expired",
                new SignInEmails.Link("a@example.test", LINK, Instant.now().minusSeconds(1), true, null, null));
        feed.request("event_2", Kind.INVITATION, "revoked",
                new SignInEmails.Link("b@example.test", LINK, Instant.now().plusSeconds(3600), false, null, null));
        feed.request("event_3", Kind.PASSWORD_RESET, "gone", null);
        outbox.readRequests();

        outbox.sendDue();

        assertThat(mail.sent()).isEmpty();
        assertThat(status("event_1")).isEqualTo("SKIPPED");
        assertThat(status("event_2")).isEqualTo("SKIPPED");
        assertThat(status("event_3")).isEqualTo("SKIPPED");
    }

    @Test
    void theSecretIsNeverStored() {
        feed.request("event_1", Kind.INVITATION, "invitation_1", feed.link("new.person@example.test", LINK));
        outbox.readRequests();
        outbox.sendDue();

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM sign_in_email");
        assertThat(rows.toString()).doesNotContain("SECRET-TOKEN");
    }

    @Test
    void aProviderThatSendsItsOwnEmailIsLeftAlone() {
        when(identity.capabilities()).thenReturn(IdentityCapabilities.none());
        feed.request("event_1", Kind.INVITATION, "invitation_1", feed.link("new.person@example.test", LINK));

        assertThat(outbox.readRequests()).isZero();
        outbox.sendDue();

        assertThat(mail.attempts()).isZero();
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private SignInEmailOutbox outbox() {
        return new SignInEmailOutbox(jdbc, transactions, identity, mapper, mail, new SignInEmailTemplates("Echno"),
                Clock.systemUTC(), false);
    }

    private String status(String request) {
        return jdbc.queryForObject("SELECT status FROM sign_in_email WHERE request_id = ?", String.class, request);
    }

    private String error(String request) {
        return jdbc.queryForObject("SELECT last_error FROM sign_in_email WHERE request_id = ?", String.class, request);
    }

    private void makeDue() {
        jdbc.update("UPDATE sign_in_email SET next_attempt_at = now() - INTERVAL '1 second' WHERE status = 'PENDING'");
    }

    private void clean() {
        jdbc.update("DELETE FROM sign_in_email");
        jdbc.update("DELETE FROM sign_in_email_cursor");
    }

    /** A provider's requests in order, honouring the cursor and the start time as the Events API does. */
    private static final class Feed implements SignInEmails {

        private final List<Request> requests = new ArrayList<>();
        private final Map<String, Link> links = new HashMap<>();

        Link link(String email, String secret) {
            return new Link(email, secret, Instant.now().plus(Duration.ofDays(7)), true, null, null);
        }

        void request(String id, Kind kind, String objectId, @Nullable Link link) {
            request(id, kind, objectId, link, Instant.now());
        }

        synchronized void request(String id, Kind kind, String objectId, @Nullable Link link, Instant at) {
            requests.add(new Request(id, kind, objectId, at));
            if (link != null) {
                links.put(objectId, link);
            }
        }

        @Override
        public synchronized Page requestsAfter(@Nullable String cursor, Instant since, int limit) {
            int start = 0;
            if (cursor != null) {
                for (int i = 0; i < requests.size(); i++) {
                    if (requests.get(i).id().equals(cursor)) {
                        start = i + 1;
                    }
                }
            }
            List<Request> page = requests.subList(start, requests.size()).stream()
                    .filter(r -> cursor != null || !r.createdAt().isBefore(since))
                    .limit(limit).toList();
            return new Page(page, page.isEmpty() ? cursor : page.get(page.size() - 1).id());
        }

        @Override
        public synchronized Optional<Link> link(Kind kind, String objectId) {
            return Optional.ofNullable(links.get(objectId));
        }
    }
}
