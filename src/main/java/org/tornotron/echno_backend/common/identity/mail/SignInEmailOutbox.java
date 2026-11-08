package org.tornotron.echno_backend.common.identity.mail;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.identity.ExternalUserId;
import org.tornotron.echno_backend.common.identity.IdentityProvider;
import org.tornotron.echno_backend.common.identity.IdentityTokenMapper;
import org.tornotron.echno_backend.common.identity.SignInEmails;
import org.tornotron.echno_backend.common.identity.SignInEmails.Kind;
import org.tornotron.echno_backend.common.mail.ConditionalOnMailSender;
import org.tornotron.echno_backend.common.mail.MailSendException;
import org.tornotron.echno_backend.common.mail.MailSender;
import org.tornotron.echno_backend.common.mail.MailSettings;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends the identity provider's sign-in email (invitation, password reset, email verification) through
 * Echno's own {@link MailSender}, for a provider whose own email is switched off.
 *
 * <p>Each pass does two things, both safe to run on several replicas at once:
 * <ol>
 *   <li><b>Read.</b> The provider's new requests go into {@code sign_in_email}, one row per request id,
 *       in the same transaction that moves the cursor, and only if the cursor is still where this pass
 *       found it. Two replicas reading at once write the rows once.</li>
 *   <li><b>Send.</b> Each due row is claimed with one statement, the link or code is read from the
 *       provider, and the email goes. A failure is tried again with a growing wait. A refusal the same
 *       message cannot get past, or too many attempts, marks the row {@code FAILED}, which the
 *       invitation list shows the administrator. A request whose link expired or was withdrawn before
 *       it could go is {@code SKIPPED}.</li>
 * </ol>
 *
 * <p>The link or code is never stored and never logged: it is read for the one email and dropped.
 * Delivery is at least once. A send that went, followed by a failure to record it, sends again once
 * the claim runs out.
 *
 * <p>{@code echno.mail.sign-in.enabled=false} stops the schedule, which the test suite does; a test
 * calls {@link #readRequests()} and {@link #sendDue()} itself.
 */
@Component
@ConditionalOnMailSender
@Slf4j
public class SignInEmailOutbox {

    static final int PAGE_SIZE = 100;
    private static final int MOST_PAGES_PER_PASS = 10;
    private static final int SEND_BATCH = 20;
    /** Longer than one provider read and one send, both of which are bounded far below this. */
    private static final Duration CLAIM = Duration.ofMinutes(2);
    private static final Duration FIRST_WAIT = Duration.ofSeconds(15);
    private static final Duration LONGEST_WAIT = Duration.ofMinutes(30);
    /** About six hours of trying. A verification code is long dead by then; a link may not be. */
    static final int MOST_ATTEMPTS = 15;
    /** The Events API answers at most 30 days from a start time, so an idle cursor is moved up. */
    private static final Duration LONGEST_START_GAP = Duration.ofDays(20);
    static final Duration FIRST_START_LEAD = Duration.ofMinutes(15);
    private static final int ERROR_LENGTH = 500;
    private static final int FINISHED_ROWS_KEPT_DAYS = 90;

    private record Due(UUID id, Kind kind, String objectId, int attempts) {
    }

    private record Cursor(@Nullable String last, Instant startedAt) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final IdentityProvider identity;
    private final IdentityTokenMapper mapper;
    private final MailSender mail;
    private final SignInEmailTemplates templates;
    private final Clock clock;
    private final boolean enabled;
    private final AtomicInteger recentFailures = new AtomicInteger();
    private volatile boolean warnedNoFeed;

    @Autowired
    public SignInEmailOutbox(JdbcTemplate jdbc, PlatformTransactionManager transactions, IdentityProvider identity,
                             IdentityTokenMapper mapper, MailSender mail, MailSettings settings,
                             ObjectProvider<MeterRegistry> meters,
                             @Value("${echno.mail.sign-in.enabled:true}") boolean enabled) {
        this(jdbc, transactions, identity, mapper, mail, new SignInEmailTemplates(settings.product()),
                Clock.systemUTC(), enabled);
        meters.ifAvailable(registry -> Gauge.builder("echno.sign_in_email.failed", recentFailures, AtomicInteger::get)
                .description("Sign-in emails that gave up in the last 7 days")
                .register(registry));
    }

    SignInEmailOutbox(JdbcTemplate jdbc, PlatformTransactionManager transactions, IdentityProvider identity,
                      IdentityTokenMapper mapper, MailSender mail, SignInEmailTemplates templates, Clock clock,
                      boolean enabled) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.identity = identity;
        this.mapper = mapper;
        this.mail = mail;
        this.templates = templates;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${echno.mail.sign-in.poll-ms:10000}")
    void onSchedule() {
        if (!enabled) {
            return;
        }
        try {
            readRequests();
            sendDue();
        } catch (RuntimeException e) {
            log.error("Sign-in email pass failed; the next one tries again", e);
        }
    }

    // --- Read ------------------------------------------------------------------------------------

    /** Reads the provider's new requests into the outbox. Returns how many were read. */
    public int readRequests() {
        Optional<SignInEmails> feed = feed();
        if (feed.isEmpty()) {
            return 0;
        }
        Cursor cursor = cursor();
        int read = 0;
        for (int page = 0; page < MOST_PAGES_PER_PASS; page++) {
            SignInEmails.Page next = feed.get().requestsAfter(cursor.last(), cursor.startedAt(), PAGE_SIZE);
            if (next.cursor() == null || next.cursor().equals(cursor.last())) {
                moveIdleStart(cursor);
                break;
            }
            if (!record(cursor, next)) {
                break;
            }
            read += next.requests().size();
            cursor = new Cursor(next.cursor(), cursor.startedAt());
        }
        return read;
    }

    /** The rows and the cursor move together, and only from where this pass found the cursor. */
    private boolean record(Cursor from, SignInEmails.Page page) {
        try {
            return Boolean.TRUE.equals(transaction.execute(status -> {
                int moved = jdbc.update("""
                        UPDATE sign_in_email_cursor SET last_request_id = ?, updated_at = now()
                        WHERE provider = ? AND COALESCE(last_request_id, '') = ?
                        """, page.cursor(), mapper.provider(), from.last() == null ? "" : from.last());
                if (moved != 1) {
                    status.setRollbackOnly();
                    return false;
                }
                page.requests().forEach(request -> jdbc.update("""
                        INSERT INTO sign_in_email (id, request_id, kind, provider_object_id, status, attempts,
                                                   next_attempt_at, requested_at, created_at)
                        VALUES (gen_random_uuid(), ?, ?, ?, 'PENDING', 0, now(), ?, now())
                        ON CONFLICT (request_id) DO NOTHING
                        """, request.id(), request.kind().name(), request.objectId(),
                        Timestamp.from(request.createdAt())));
                return true;
            }));
        } catch (RuntimeException raced) {
            log.info("Another pass recorded these sign-in email requests first");
            return false;
        }
    }

    /**
     * The first pass starts a little before itself, so a request made while the application was still
     * starting (the first administrator's invitation, sent by the identity task queue) is not missed.
     */
    private Cursor cursor() {
        jdbc.update("""
                INSERT INTO sign_in_email_cursor (provider, last_request_id, started_at, updated_at)
                VALUES (?, NULL, now() - (? * INTERVAL '1 second'), now()) ON CONFLICT (provider) DO NOTHING
                """, mapper.provider(), FIRST_START_LEAD.toSeconds());
        return jdbc.queryForObject("SELECT last_request_id, started_at FROM sign_in_email_cursor WHERE provider = ?",
                (rs, index) -> new Cursor(rs.getString(1), rs.getTimestamp(2).toInstant()), mapper.provider());
    }

    /** With nothing read yet, keeps the start inside the window the provider can answer for. */
    private void moveIdleStart(Cursor cursor) {
        Instant now = clock.instant();
        if (cursor.last() == null && cursor.startedAt().isBefore(now.minus(LONGEST_START_GAP))) {
            jdbc.update("""
                    UPDATE sign_in_email_cursor SET started_at = ?, updated_at = now()
                    WHERE provider = ? AND last_request_id IS NULL
                    """, Timestamp.from(now.minus(Duration.ofDays(1))), mapper.provider());
        }
    }

    // --- Send ------------------------------------------------------------------------------------

    /** Sends everything due, oldest request first. */
    public void sendDue() {
        Optional<SignInEmails> feed = feed();
        if (feed.isEmpty()) {
            return;
        }
        List<Due> due = jdbc.query("""
                SELECT id, kind, provider_object_id, attempts FROM sign_in_email
                WHERE status = 'PENDING' AND next_attempt_at <= now()
                ORDER BY requested_at LIMIT ?
                """, (rs, index) -> new Due(rs.getObject(1, UUID.class), Kind.valueOf(rs.getString(2)),
                rs.getString(3), rs.getInt(4)), SEND_BATCH);
        due.stream().filter(this::claim).forEach(row -> send(feed.get(), row));
        jdbc.update("DELETE FROM sign_in_email WHERE status <> 'PENDING' AND finished_at < now() - (? * INTERVAL '1 day')",
                FINISHED_ROWS_KEPT_DAYS);
        Integer failed = jdbc.queryForObject("""
                SELECT count(*) FROM sign_in_email WHERE status = 'FAILED' AND finished_at > now() - INTERVAL '7 days'
                """, Integer.class);
        recentFailures.set(failed == null ? 0 : failed);
    }

    private boolean claim(Due row) {
        return jdbc.update("""
                UPDATE sign_in_email SET attempts = attempts + 1, next_attempt_at = now() + (? * INTERVAL '1 second')
                WHERE id = ? AND status = 'PENDING' AND next_attempt_at <= now()
                """, CLAIM.toSeconds(), row.id()) == 1;
    }

    private void send(SignInEmails feed, Due row) {
        int attempt = row.attempts() + 1;
        try {
            Optional<SignInEmails.Link> found = feed.link(row.kind(), row.objectId());
            if (found.isEmpty()) {
                finish(row, attempt, "SKIPPED", "The identity provider no longer has it", null);
                return;
            }
            SignInEmails.Link link = found.get();
            if (!link.open()) {
                finish(row, attempt, "SKIPPED", "No longer pending at the identity provider", null);
                return;
            }
            if (link.expiresAt() != null && !link.expiresAt().isAfter(clock.instant())) {
                finish(row, attempt, "SKIPPED", "Expired before it could be sent", null);
                return;
            }
            String messageId = mail.send(templates.render(row.kind(), new SignInEmailTemplates.Values(
                    link.email(), link.secret(), link.expiresAt(), organizationName(link.organizationId()),
                    inviterName(link.inviter()))));
            finish(row, attempt, "SENT", null, messageId);
            log.info("Sign-in email {} {} sent as {}", row.kind(), row.id(), messageId);
        } catch (MailSendException refused) {
            fail(row, attempt, refused.getMessage(), refused.permanent());
        } catch (RuntimeException failed) {
            fail(row, attempt, failed.getClass().getSimpleName() + ": " + failed.getMessage(), false);
        }
    }

    private void fail(Due row, int attempt, String error, boolean permanent) {
        String reason = truncated(error);
        if (permanent || attempt >= MOST_ATTEMPTS) {
            log.error("Sign-in email {} {} gave up after {} attempts: {}", row.kind(), row.id(), attempt, reason);
            finish(row, attempt, "FAILED", reason, null);
            return;
        }
        log.warn("Sign-in email {} {} failed on attempt {}: {}", row.kind(), row.id(), attempt, reason);
        jdbc.update("""
                UPDATE sign_in_email SET next_attempt_at = now() + (? * INTERVAL '1 second'), last_error = ?
                WHERE id = ? AND attempts = ?
                """, waitAfter(attempt).toSeconds(), reason, row.id(), attempt);
    }

    private void finish(Due row, int attempt, String status, @Nullable String error, @Nullable String messageId) {
        jdbc.update("""
                UPDATE sign_in_email SET status = ?, last_error = ?, message_id = ?, finished_at = now()
                WHERE id = ? AND attempts = ?
                """, status, error == null ? null : truncated(error), messageId, row.id(), attempt);
    }

    // --- What the email names --------------------------------------------------------------------

    @Nullable
    private String organizationName(@Nullable String providerOrganization) {
        if (providerOrganization == null) {
            return null;
        }
        return jdbc.queryForList("""
                SELECT o.organization_name FROM organization_identity i JOIN organization o ON o.id = i.organization_id
                WHERE i.provider = ? AND i.provider_org_id = ?
                """, String.class, mapper.provider(), providerOrganization).stream().filter(Objects::nonNull)
                .findFirst().orElse(null);
    }

    @Nullable
    private String inviterName(@Nullable ExternalUserId inviter) {
        if (inviter == null) {
            return null;
        }
        return jdbc.queryForList("""
                SELECT u.name FROM user_identity i JOIN users_table u ON u.id = i.user_id
                WHERE i.issuer = ? AND i.subject = ?
                """, String.class, mapper.issuer(), inviter.value()).stream().filter(Objects::nonNull)
                .findFirst().orElse(null);
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private Optional<SignInEmails> feed() {
        Optional<SignInEmails> feed = identity.capabilities().signInEmails();
        if (feed.isEmpty() && !warnedNoFeed) {
            warnedNoFeed = true;
            log.warn("echno.mail.provider is set, but the {} identity provider sends its own sign-in email, "
                    + "so Echno sends none", mapper.provider());
        }
        return feed;
    }

    static Duration waitAfter(int attempts) {
        Duration wait = FIRST_WAIT.multipliedBy(1L << Math.min(attempts - 1, 20));
        return wait.compareTo(LONGEST_WAIT) > 0 ? LONGEST_WAIT : wait;
    }

    private static String truncated(String error) {
        String message = error == null ? "unknown" : error;
        return message.length() <= ERROR_LENGTH ? message : message.substring(0, ERROR_LENGTH);
    }
}
