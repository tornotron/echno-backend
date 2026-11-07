package org.tornotron.echno_backend.common.startup;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

/** The lock and the failure rules on CockroachDB. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(StartupTaskState.class)
class StartupTaskRunnerIT extends AbstractIntegrationTest {

    private static final Duration AN_HOUR = Duration.ofHours(1);

    @Autowired
    private StartupTaskState state;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<String> ran = new ArrayList<>();

    @BeforeEach
    void noRowsLeftBehind() {
        jdbc.update("DELETE FROM startup_task_state WHERE name IN (?, 'first', 'broken', 'after-broken')",
                StartupTaskState.LOCK);
    }

    private StartupTask task(String name) {
        return new StartupTask() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void run() {
                ran.add(name);
            }
        };
    }

    private StartupTaskRunner runner(StartupTask... tasks) {
        return new StartupTaskRunner(List.of(tasks), state, true);
    }

    @Test
    void aFailingTaskStopsOnlyItselfAndIsNotRecorded() {
        StartupTask broken = new StartupTask() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public void run() {
                throw new IllegalStateException("the provider is down");
            }
        };

        assertThat(runner(task("first"), broken, task("after-broken")).runOnce()).isTrue();

        assertThat(ran).containsExactly("first", "after-broken");
        assertThat(state.hasCompleted("first")).isTrue();
        assertThat(state.hasCompleted("broken")).isFalse();
        assertThat(state.hasCompleted("after-broken")).isTrue();
    }

    @Test
    void aHeldLockKeepsAnotherReplicaOut() {
        assertThat(state.tryLock("other-replica", AN_HOUR)).isTrue();

        assertThat(runner(task("first")).runOnce()).isFalse();

        assertThat(ran).isEmpty();
    }

    @Test
    void aLeaseThatRanOutIsTakenOver() {
        assertThat(state.tryLock("dead-replica", Duration.ofSeconds(-1))).isTrue();

        assertThat(runner(task("first")).runOnce()).isTrue();

        assertThat(ran).containsExactly("first");
    }

    @Test
    void theLockIsFreeAfterARun() {
        assertThat(runner(task("first")).runOnce()).isTrue();
        assertThat(state.tryLock("next-replica", AN_HOUR)).isTrue();
    }

    @Test
    void onlyOneOfTwoCompetingReplicasGetsTheLock() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Boolean> first = new AtomicReference<>();
        AtomicReference<Boolean> second = new AtomicReference<>();
        Thread a = competitor("a", start, first);
        Thread b = competitor("b", start, second);
        start.countDown();
        a.join(TimeUnit.SECONDS.toMillis(30));
        b.join(TimeUnit.SECONDS.toMillis(30));

        assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
    }

    private Thread competitor(String holder, CountDownLatch start, AtomicReference<Boolean> result) {
        Thread thread = new Thread(() -> {
            try {
                start.await();
                result.set(state.tryLock(holder, AN_HOUR));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        thread.start();
        return thread;
    }

    @Test
    void theAutomaticRunReturnsAtOnceAndDoesItsWorkElsewhere() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        StartupTask slow = new StartupTask() {
            @Override
            public String name() {
                return "first";
            }

            @Override
            public void run() {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                finished.countDown();
            }
        };

        runner(slow).run(new DefaultApplicationArguments());

        assertThat(finished.getCount()).isEqualTo(1);
        release.countDown();
        assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void aRunLeavesNoTenantDeclarationBehind() {
        runner(task("first")).runOnce();

        assertThat(TenantContext.isScopeDeclared()).isFalse();
    }

    @Test
    void turnedOffNothingRuns() throws Exception {
        new StartupTaskRunner(List.of(task("first")), state, false).run(new DefaultApplicationArguments());

        assertThat(ran).isEmpty();
    }
}
