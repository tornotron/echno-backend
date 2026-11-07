package org.tornotron.echno_backend.common.identity.task;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.identity.EchnoOrgId;
import org.tornotron.echno_backend.common.identity.ExternalUserId;
import org.tornotron.echno_backend.common.identity.IdentityProvider;
import org.tornotron.echno_backend.common.identity.task.IdentityTaskQueue.Kind;

/**
 * Makes the calls {@link IdentityTaskQueue} asked for. Each call runs outside any transaction, after
 * claiming its row with a single statement, so two replicas do not both make it and a replica that dies
 * mid-call only delays it. A call that fails is tried again with a growing wait, and every call here is
 * safe to repeat, so a claim that ran out while the call was still going does no harm.
 *
 * <p>{@code echno.identity.tasks.enabled=false} stops the schedule, which the test suite does; a test
 * calls {@link #runDue()} itself.
 */
@Component
@Slf4j
public class IdentityTaskWorker {

    /** Long enough for one call to finish, which the provider clients bound far below this. */
    private static final Duration CLAIM = Duration.ofMinutes(5);
    private static final Duration FIRST_WAIT = Duration.ofSeconds(30);
    private static final Duration LONGEST_WAIT = Duration.ofHours(1);
    /** About a day of trying, at the longest wait. After that a person should look at it. */
    static final int MOST_ATTEMPTS = 30;
    private static final int BATCH = 50;
    private static final int ERROR_LENGTH = 500;
    private static final int FINISHED_ROWS_KEPT_DAYS = 30;

    private record Task(UUID id, Kind kind, String account, Long userId, Long organizationId, String role, int attempts) {
    }

    private final JdbcTemplate jdbc;
    private final IdentityProvider provider;
    private final boolean enabled;

    public IdentityTaskWorker(JdbcTemplate jdbc, IdentityProvider provider,
                              @Value("${echno.identity.tasks.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.provider = provider;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${echno.identity.tasks.poll-ms:30000}")
    void onSchedule() {
        if (!enabled) {
            return;
        }
        try {
            runDue();
        } catch (RuntimeException e) {
            log.error("Identity task pass failed; the next one tries again", e);
        }
    }

    /** One pass over everything due, oldest first. */
    public void runDue() {
        List<Task> due = jdbc.query("""
                SELECT id, kind, account, user_id, organization_id, org_role, attempts
                FROM identity_task
                WHERE status = 'PENDING' AND next_attempt_at <= now()
                ORDER BY created_at
                LIMIT ?
                """, (row, index) -> new Task(row.getObject(1, UUID.class), Kind.valueOf(row.getString(2)),
                row.getString(3), row.getObject(4, Long.class), row.getObject(5, Long.class),
                row.getString(6), row.getInt(7)), BATCH);
        due.stream().filter(this::claim).forEach(this::run);
        jdbc.update("DELETE FROM identity_task WHERE status <> 'PENDING' AND finished_at < now() - (? * INTERVAL '1 day')",
                FINISHED_ROWS_KEPT_DAYS);
    }

    private boolean claim(Task task) {
        return jdbc.update("""
                UPDATE identity_task SET attempts = attempts + 1, next_attempt_at = now() + (? * INTERVAL '1 second')
                WHERE id = ? AND status = 'PENDING' AND next_attempt_at <= now()
                """, CLAIM.toSeconds(), task.id()) == 1;
    }

    private void run(Task task) {
        try {
            call(task);
            finish(task, "DONE", null);
        } catch (RuntimeException e) {
            fail(task, e);
        }
    }

    private void call(Task task) {
        ExternalUserId account = new ExternalUserId(task.account());
        switch (task.kind()) {
            case REMOVE_MEMBER -> provider.removeMember(account, new EchnoOrgId(task.organizationId()));
            case END_SESSIONS -> {
                if (!hasActiveEmployee(task.userId())) {
                    provider.endSessions(account);
                }
            }
            case ASSIGN_ROLE -> provider.capabilities().orgRoleGroups().ifPresent(groups -> groups.assign(
                    account, new EchnoOrgId(task.organizationId()), OrgRole.valueOf(task.role())));
            case REMOVE_ROLE -> provider.capabilities().orgRoleGroups().ifPresent(groups -> groups.remove(
                    account, new EchnoOrgId(task.organizationId()), OrgRole.valueOf(task.role())));
        }
    }

    private boolean hasActiveEmployee(Long userId) {
        Integer active = jdbc.queryForObject("""
                SELECT count(*) FROM employee WHERE user_id = ? AND status IN ('active', 'onLeave', 'probation')
                """, Integer.class, userId);
        return active != null && active > 0;
    }

    /**
     * Applies only if nobody has replaced the request since this attempt claimed it. A replacement stays
     * pending and runs after this call, so the latest request is always the last to reach the provider.
     */
    private void finish(Task task, String status, String error) {
        jdbc.update("""
                UPDATE identity_task SET status = ?, finished_at = now(), last_error = ?
                WHERE id = ? AND kind = ? AND attempts = ?
                """, status, error, task.id(), task.kind().name(), task.attempts() + 1);
    }

    private void fail(Task task, RuntimeException failure) {
        String error = truncated(failure);
        int attempts = task.attempts() + 1;
        if (attempts >= MOST_ATTEMPTS) {
            log.error("Identity task {} {} gave up after {} attempts: {}", task.kind(), task.id(), attempts, error);
            finish(task, "FAILED", error);
            return;
        }
        log.warn("Identity task {} {} failed on attempt {}: {}", task.kind(), task.id(), attempts, error);
        jdbc.update("""
                UPDATE identity_task SET next_attempt_at = now() + (? * INTERVAL '1 second'), last_error = ?
                WHERE id = ? AND kind = ? AND attempts = ?
                """, waitAfter(attempts).toSeconds(), error, task.id(), task.kind().name(), attempts);
    }

    static Duration waitAfter(int attempts) {
        Duration wait = FIRST_WAIT.multipliedBy(1L << Math.min(attempts - 1, 20));
        return wait.compareTo(LONGEST_WAIT) > 0 ? LONGEST_WAIT : wait;
    }

    private static String truncated(RuntimeException failure) {
        String message = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        return message.length() <= ERROR_LENGTH ? message : message.substring(0, ERROR_LENGTH);
    }
}
