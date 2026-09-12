package org.tornotron.echno_backend.modules.bim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.bim.domain.BimImportJob;
import org.tornotron.echno_backend.modules.bim.domain.BimModelVersion;

/**
 * The transitions the import contract allows and nothing else. The worker owns the forward
 * moves, the backend the lease recoveries; a terminal row never reopens.
 */
class BimImportJobStateMachineTest {

    @Test
    void theWorkerPathIsQueuedRunningDone() {
        BimImportJob job = new BimImportJob();
        assertThat(job.getStatus()).isEqualTo(BimImportJobStatus.QUEUED);
        job.transitionTo(BimImportJobStatus.RUNNING);
        job.transitionTo(BimImportJobStatus.DONE);
        assertThat(job.getStatus().isTerminal()).isTrue();
    }

    @Test
    void aRunningJobCanFailOrBeRequeuedButAQueuedOneCannotFinish() {
        BimImportJob failing = new BimImportJob();
        failing.transitionTo(BimImportJobStatus.RUNNING);
        failing.transitionTo(BimImportJobStatus.FAILED);

        BimImportJob requeued = new BimImportJob();
        requeued.transitionTo(BimImportJobStatus.RUNNING);
        requeued.transitionTo(BimImportJobStatus.QUEUED);
        assertThat(requeued.getStatus()).isEqualTo(BimImportJobStatus.QUEUED);

        assertThatIllegalStateException()
                .isThrownBy(() -> new BimImportJob().transitionTo(BimImportJobStatus.DONE))
                .withMessageContaining("QUEUED to DONE");
    }

    @Test
    void terminalRowsNeverReopen() {
        for (BimImportJobStatus terminal : new BimImportJobStatus[]{BimImportJobStatus.DONE, BimImportJobStatus.FAILED}) {
            assertThat(terminal.allowedNext()).isEmpty();
            for (BimImportJobStatus next : BimImportJobStatus.values()) {
                assertThat(terminal.canTransitionTo(next)).as("%s -> %s", terminal, next).isFalse();
            }
        }
    }

    @Test
    void attemptsAreCountedAgainstTheMaximum() {
        BimImportJob job = new BimImportJob();
        assertThat(job.hasAttemptsLeft()).isTrue();
        job.setAttempt(BimImportJob.DEFAULT_MAX_ATTEMPTS);
        assertThat(job.hasAttemptsLeft()).isFalse();
    }

    @Test
    void theVersionMirrorsTheJobAndOnlyAFailedVersionRequeues() {
        BimModelVersion v = new BimModelVersion();
        assertThat(v.getStatus()).isEqualTo(BimVersionStatus.UPLOADED);
        v.transitionTo(BimVersionStatus.QUEUED);
        v.transitionTo(BimVersionStatus.PROCESSING);
        v.transitionTo(BimVersionStatus.INGESTING);
        v.transitionTo(BimVersionStatus.READY);
        assertThat(BimVersionStatus.READY.allowedNext()).isEmpty();

        BimModelVersion failed = new BimModelVersion();
        failed.transitionTo(BimVersionStatus.QUEUED);
        failed.transitionTo(BimVersionStatus.FAILED);
        failed.transitionTo(BimVersionStatus.QUEUED);

        assertThatIllegalStateException()
                .isThrownBy(() -> new BimModelVersion().transitionTo(BimVersionStatus.READY))
                .withMessageContaining("UPLOADED to READY");
    }
}
