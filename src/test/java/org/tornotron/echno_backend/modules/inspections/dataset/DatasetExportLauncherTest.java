package org.tornotron.echno_backend.modules.inspections.dataset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The launcher runs a started export under the organization's tenant and off the caller (#812). */
@ExtendWith(MockitoExtension.class)
class DatasetExportLauncherTest {

    @Mock private DatasetExportService exportService;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void runsTheExportUnderTheOrganizationsTenantOnTheExecutor() {
        UUID runId = UUID.randomUUID();
        List<Long> tenantsSeen = new ArrayList<>();
        List<Thread> threadsSeen = new ArrayList<>();
        when(exportService.execute(runId, 7L)).thenAnswer(inv -> {
            tenantsSeen.add(TenantContext.getCurrentOrgId());
            threadsSeen.add(Thread.currentThread());
            return null;
        });
        List<Runnable> queued = new ArrayList<>();

        new DatasetExportLauncher(exportService, new TenantScopedJobRunner(), queued::add).launch(runId, 7L);

        // returned before the work ran, with nothing done on the caller's thread
        assertThat(tenantsSeen).isEmpty();
        assertThat(queued).hasSize(1);
        queued.get(0).run();
        verify(exportService).execute(runId, 7L);
        assertThat(tenantsSeen).containsExactly(7L);
        assertThat(TenantContext.getCurrentOrgId()).isNull();
    }

    @Test
    void aFailureOutsideTheRunIsLoggedAndDoesNotEscapeTheExecutor() {
        UUID runId = UUID.randomUUID();
        when(exportService.execute(eq(runId), any())).thenThrow(new IllegalStateException("context gone"));
        List<Runnable> queued = new ArrayList<>();

        new DatasetExportLauncher(exportService, new TenantScopedJobRunner(), queued::add).launch(runId, 7L);

        assertThatCode(() -> queued.get(0).run()).doesNotThrowAnyException();
        assertThat(TenantContext.getCurrentOrgId()).isNull();
    }
}
