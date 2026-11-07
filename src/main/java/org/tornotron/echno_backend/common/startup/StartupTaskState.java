package org.tornotron.echno_backend.common.startup;

import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The startup tasks' row in the database: a lock with a lease, and a record of which tasks have
 * finished. Every call here is one statement outside any transaction, so it cannot be caught up in a
 * task's retries.
 *
 * <p>The lock saves work rather than protects data, since the tasks are repeat-safe anyway. A lease
 * means a replica that dies holding it delays the next run and never prevents it.
 */
@Component
public class StartupTaskState {

    static final String LOCK = "startup-tasks";

    private final JdbcTemplate jdbc;

    public StartupTaskState(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Takes the lock unless another holder's lease is still running. */
    boolean tryLock(String holder, Duration lease) {
        return jdbc.update("""
                INSERT INTO startup_task_state (name, holder, expires_at)
                VALUES (?, ?, now() + (? * INTERVAL '1 second'))
                ON CONFLICT (name) DO UPDATE
                SET holder = excluded.holder, expires_at = excluded.expires_at
                WHERE startup_task_state.expires_at IS NULL OR startup_task_state.expires_at < now()
                """, LOCK, holder, lease.toSeconds()) == 1;
    }

    /** Extends the lease, between tasks that can each take a while. */
    void renew(String holder, Duration lease) {
        jdbc.update("""
                UPDATE startup_task_state SET expires_at = now() + (? * INTERVAL '1 second')
                WHERE name = ? AND holder = ?
                """, lease.toSeconds(), LOCK, holder);
    }

    void unlock(String holder) {
        jdbc.update("UPDATE startup_task_state SET holder = NULL, expires_at = NULL WHERE name = ? AND holder = ?",
                LOCK, holder);
    }

    void markCompleted(String task) {
        jdbc.update("""
                INSERT INTO startup_task_state (name, completed_at) VALUES (?, now())
                ON CONFLICT (name) DO UPDATE SET completed_at = excluded.completed_at
                """, task);
    }

    /** Whether the named task has finished at least once on this database. */
    public boolean hasCompleted(String task) {
        Integer finished = jdbc.queryForObject(
                "SELECT count(*) FROM startup_task_state WHERE name = ? AND completed_at IS NOT NULL",
                Integer.class, task);
        return finished != null && finished > 0;
    }
}
