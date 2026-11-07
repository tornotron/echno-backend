package org.tornotron.echno_backend.common.startup;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;

/**
 * Runs the {@link StartupTask}s after every boot, and can never stop a boot.
 *
 * <ul>
 *   <li>They run on a thread of their own, so a slow database or a provider that does not answer
 *       delays the tasks and not readiness. The application serves requests while they run, so
 *       whatever reads their output must cope with it being unfinished.
 *   <li>Everything is caught and logged. A task that fails does not stop the next one, and the next
 *       boot runs it again.
 *   <li>A lock row keeps two replicas from doing the same work at once. A replica that finds the
 *       lock taken waits its turn, since the holder may have died part way, and then checks for
 *       itself. By then there is normally nothing to do.
 * </ul>
 *
 * <p>{@code echno.startup-tasks.enabled=false} turns the automatic run off, which the test suite does
 * so that a context starting up does not write to a database other tests are using.
 */
@Component
@Slf4j
public class StartupTaskRunner implements ApplicationRunner {

    private static final Duration LEASE = Duration.ofMinutes(15);
    private static final Duration WAIT_BETWEEN_ATTEMPTS = Duration.ofMinutes(1);
    private static final int ATTEMPTS = 20;

    private final List<StartupTask> tasks;
    private final StartupTaskState state;
    private final boolean enabled;

    @Autowired
    public StartupTaskRunner(ObjectProvider<StartupTask> tasks, StartupTaskState state,
                             @Value("${echno.startup-tasks.enabled:true}") boolean enabled) {
        this(tasks.orderedStream().toList(), state, enabled);
    }

    StartupTaskRunner(List<StartupTask> tasks, StartupTaskState state, boolean enabled) {
        this.tasks = tasks;
        this.state = state;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || tasks.isEmpty()) {
            return;
        }
        Thread thread = new Thread(this::runWhenTheLockIsFree, "startup-tasks");
        thread.setDaemon(true);
        thread.start();
    }

    private void runWhenTheLockIsFree() {
        try {
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                if (runOnce()) {
                    return;
                }
                log.info("Another replica holds the startup task lock; trying again in {}", WAIT_BETWEEN_ATTEMPTS);
                Thread.sleep(WAIT_BETWEEN_ATTEMPTS);
            }
            log.warn("The startup task lock stayed taken; the next boot runs the tasks");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("The startup tasks could not run; the next boot tries again", e);
        }
    }

    /**
     * One pass over every task, if the lock is free. Runs on the calling thread, which is how a test
     * drives it.
     *
     * @return whether the pass ran, false when another holder has the lock.
     */
    public boolean runOnce() {
        String holder = "replica-" + UUID.randomUUID();
        if (!state.tryLock(holder, LEASE)) {
            return false;
        }
        TenantContext.declareUnscoped("A startup task works across every organization, one at a time");
        try {
            for (StartupTask task : tasks) {
                state.renew(holder, LEASE);
                runOne(task);
            }
            return true;
        } finally {
            TenantContext.clear();
            state.unlock(holder);
        }
    }

    private void runOne(StartupTask task) {
        try {
            task.run();
            state.markCompleted(task.name());
            log.info("Startup task {} finished", task.name());
        } catch (RuntimeException e) {
            log.error("Startup task {} failed; the next boot runs it again", task.name(), e);
        }
    }
}
