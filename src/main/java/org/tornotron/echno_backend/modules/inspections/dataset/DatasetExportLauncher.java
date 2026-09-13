package org.tornotron.echno_backend.modules.inspections.dataset;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a started export off the request thread (#812). The endpoint records the run and
 * answers 202 with it; the copying happens here, under the organization's tenant, and the row
 * carries the outcome for the status endpoint to read.
 *
 * <p>Its own small pool rather than the shared {@code applicationTaskExecutor}: a run of a few
 * hundred S3 copies takes minutes, and it must not sit in front of the short {@code @Async}
 * work the shared pool serves. The pool is not awaited on shutdown: a run cut off mid-way is
 * left {@code running} and {@link DatasetExportService#start} closes it as failed once it is
 * stale; what it committed stands and the next run skips it.
 */
@Slf4j
@Component
public class DatasetExportLauncher {

    private final DatasetExportService exportService;
    private final TenantScopedJobRunner tenantScopedJobRunner;
    private final Executor executor;
    private final ExecutorService owned;

    @Autowired
    public DatasetExportLauncher(DatasetExportService exportService, TenantScopedJobRunner tenantScopedJobRunner,
                                 DatasetExportProperties properties) {
        this.exportService = exportService;
        this.tenantScopedJobRunner = tenantScopedJobRunner;
        AtomicInteger seq = new AtomicInteger();
        this.owned = Executors.newFixedThreadPool(Math.max(1, properties.getWorkers()), runnable -> {
            Thread thread = new Thread(runnable, "dataset-export-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.executor = owned;
    }

    /** For tests: run on the given executor (a direct one keeps the test on its own thread). */
    DatasetExportLauncher(DatasetExportService exportService, TenantScopedJobRunner tenantScopedJobRunner, Executor executor) {
        this.exportService = exportService;
        this.tenantScopedJobRunner = tenantScopedJobRunner;
        this.executor = executor;
        this.owned = null;
    }

    /**
     * Hands a run that {@link DatasetExportService#start} recorded to the pool. Returns at once.
     * The organization id comes from the run row the caller just wrote, never from the thread.
     */
    public void launch(UUID runId, Long organizationId) {
        executor.execute(() -> {
            try {
                tenantScopedJobRunner.runForTenant(organizationId, () -> exportService.execute(runId, organizationId));
            } catch (RuntimeException e) {
                // execute() records its own failure on the row; this is for anything outside it.
                log.error("Dataset export run {} for organization {} could not be run: {}", runId, organizationId,
                        e.getMessage(), e);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        if (owned != null) {
            owned.shutdownNow();
        }
    }
}
