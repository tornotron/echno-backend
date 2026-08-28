package org.tornotron.echno_backend.common.multitenancy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.exception.TenantIdMissingException;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The behaviour these tests pin down is what stands between background work and a silent
 * cross-tenant read. Both isolation mechanisms fail open on a missing tenant context, so
 * the runner has to refuse a null org id loudly, and it has to put the thread back the way
 * it found it whatever the work does, because the threads it runs on are pooled.
 */
class TenantScopedJobRunnerTest {

    private static final Long ORG_ID = 7L;
    private static final Long OTHER_ORG_ID = 9L;

    private final TenantScopedJobRunner runner = new TenantScopedJobRunner();

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void setsTheTenantForTheDurationOfTheWork() {
        AtomicReference<Long> seen = new AtomicReference<>();

        runner.runForTenant(ORG_ID, () -> seen.set(TenantContext.getCurrentOrgId()));

        assertThat(seen.get()).isEqualTo(ORG_ID);
    }

    @Test
    void returnsTheResultOfTheWork() {
        assertThat(runner.callForTenant(ORG_ID, () -> "done")).isEqualTo("done");
    }

    @Test
    void nullOrgId_refusesRatherThanRunningUnscoped() {
        AtomicReference<Boolean> ran = new AtomicReference<>(false);

        assertThatThrownBy(() -> runner.runForTenant(null, () -> ran.set(true)))
                .isInstanceOf(TenantIdMissingException.class)
                .hasMessageContaining("explicit organization id");

        assertThat(ran.get()).isFalse();
    }

    @Test
    void clearsTheContextAfterwardsSoItCannotLeakToTheNextTaskOnAPooledThread() {
        runner.runForTenant(ORG_ID, () -> {});

        assertThat(TenantContext.getCurrentOrgId()).isNull();
        assertThat(TenantContext.isBypassed()).isFalse();
    }

    @Test
    void clearsTheContextEvenWhenTheWorkThrows() {
        assertThatThrownBy(() -> runner.runForTenant(ORG_ID, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(TenantContext.getCurrentOrgId()).isNull();
    }

    @Test
    void restoresAPreExistingContextRatherThanClearingIt() {
        TenantContext.setCurrentOrgId(OTHER_ORG_ID);

        runner.runForTenant(ORG_ID, () ->
                assertThat(TenantContext.getCurrentOrgId()).isEqualTo(ORG_ID));

        assertThat(TenantContext.getCurrentOrgId()).isEqualTo(OTHER_ORG_ID);
    }

    @Test
    void forcesBypassOffForTheWorkAndRestoresIt() {
        TenantContext.setBypass(true);
        AtomicReference<Boolean> bypassedDuringWork = new AtomicReference<>();

        runner.runForTenant(ORG_ID, () -> bypassedDuringWork.set(TenantContext.isBypassed()));

        assertThat(bypassedDuringWork.get()).isFalse();
        assertThat(TenantContext.isBypassed()).isTrue();
    }
}
