package org.tornotron.echno_backend.modules.bim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.modules.bim.importer.BimImportIngestor;
import org.tornotron.echno_backend.modules.bim.importer.BimImportPipeline;
import org.tornotron.echno_backend.modules.bim.importer.BimImportPoller;
import org.tornotron.echno_backend.modules.bim.repository.BimImportJobRepository;
import org.tornotron.echno_backend.modules.bim.repository.BimImportJobRepository.JobRef;

/** The poller pins each job's tenant, recovers leases, and never lets one bad pass kill the schedule. */
class BimImportPollerTest {

    private final BimImportJobRepository jobs = mock(BimImportJobRepository.class);
    private final BimImportPipeline pipeline = mock(BimImportPipeline.class);
    private final BimImportIngestor ingestor = mock(BimImportIngestor.class);
    private final TenantScopedJobRunner runner = mock(TenantScopedJobRunner.class);
    private final BimImportPoller poller = new BimImportPoller(jobs, pipeline, ingestor, runner);

    @Test
    void doneJobsAreIngestedUnderTheirOwnTenantAndFailedOnesClosed() {
        UUID done = UUID.randomUUID();
        UUID failed = UUID.randomUUID();
        JobRef doneRef = ref(done, 7L, null);
        JobRef failedRef = ref(failed, 9L, "IfcOpenShell could not open the file");
        when(jobs.findClosedNotIngested("DONE", BimImportPoller.BATCH)).thenReturn(List.of(doneRef));
        when(jobs.findClosedNotIngested("FAILED", BimImportPoller.BATCH)).thenReturn(List.of(failedRef));
        doAnswer(inv -> {
            TenantContext.setCurrentOrgId(inv.getArgument(0));
            try {
                inv.<Runnable>getArgument(1).run();
            } finally {
                TenantContext.clear();
            }
            return null;
        }).when(runner).runForTenant(any(), any());
        doAnswer(inv -> {
            assertThat(TenantContext.getCurrentOrgId()).isEqualTo(7L);
            return null;
        }).when(pipeline).ingest(done);

        poller.poll();

        verify(pipeline).ingest(done);
        verify(ingestor).markFailed(failed, "IfcOpenShell could not open the file");
        verify(runner).runForTenant(eq(7L), any());
        verify(runner).runForTenant(eq(9L), any());
    }

    @Test
    void expiredLeasesAreRecoveredBeforeAnythingElse() {
        when(jobs.requeueExpiredLeases(any())).thenReturn(2);
        when(jobs.failExpiredLeases(any(), anyString())).thenReturn(1);

        poller.poll();

        verify(jobs).requeueExpiredLeases(any());
        verify(jobs).failExpiredLeases(any(), anyString());
        verifyNoInteractions(pipeline);
    }

    @Test
    void aFailingPassIsLoggedNotThrown() {
        doThrow(new IllegalStateException("db away")).when(jobs).requeueExpiredLeases(any());

        poller.poll();

        verifyNoInteractions(pipeline);
    }

    @Test
    void theKillSwitchAndTheIngestSwitchBothGateThePoller() {
        assertThat(BimImportPoller.class.getAnnotation(BimModuleEnabled.class)).isNotNull();
        ConditionalOnProperty own = BimImportPoller.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(own.name()).containsExactly("echno.modules.bim.ingest.enabled");
        assertThat(own.matchIfMissing()).isTrue();
    }

    private static JobRef ref(UUID id, Long org, String error) {
        JobRef ref = mock(JobRef.class);
        when(ref.getId()).thenReturn(id);
        when(ref.getOrganizationId()).thenReturn(org);
        when(ref.getError()).thenReturn(error);
        return ref;
    }
}
