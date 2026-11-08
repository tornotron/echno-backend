package org.tornotron.echno_backend.modules.documentingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mock.env.MockEnvironment;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.modules.documentingestion.job.ParseJobIngestor;
import org.tornotron.echno_backend.modules.documentingestion.job.ParseJobPoller;
import org.tornotron.echno_backend.modules.documentingestion.repository.ParseJobRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.ParseJobRepository.JobRef;

/** The poller pins each job's tenant, closes work outside the allow-list, and survives a bad pass. */
class ParseJobPollerTest {

    private final ParseJobRepository jobs = mock(ParseJobRepository.class);
    private final ParseJobIngestor ingestor = mock(ParseJobIngestor.class);
    private final TenantScopedJobRunner runner = mock(TenantScopedJobRunner.class);
    private final ParseJobPoller poller = new ParseJobPoller(jobs, ingestor, runner, new MockEnvironment()
            .withProperty("echno.modules.document-ingestion.enabled", "true")
            .withProperty("echno.modules.document-ingestion.allowed-org-ids", "7"));

    @BeforeEach
    void runnerPinsTheTenant() {
        doAnswer(inv -> {
            TenantContext.setCurrentOrgId(inv.getArgument(0));
            try {
                inv.<Runnable>getArgument(1).run();
            } finally {
                TenantContext.clear();
            }
            return null;
        }).when(runner).runForTenant(any(), any(Runnable.class));
    }

    @Test
    void existsOnlyWithTheSwitchOnAndNoDefaultToOn() {
        ConditionalOnProperty condition = AnnotatedElementUtils.findMergedAnnotation(ParseJobPoller.class,
                ConditionalOnProperty.class);
        assertThat(condition).isNotNull();
        assertThat(condition.name()).containsExactly("echno.modules.document-ingestion.enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }

    @Test
    void aClosedJobIsIngestedUnderItsOwnTenantOnceClaimed() {
        UUID id = UUID.randomUUID();
        when(jobs.findClosedNotIngested("DONE", ParseJobPoller.BATCH)).thenReturn(List.of(ref(id, 7L)));
        when(jobs.claimForIngest(eq(id), eq(7L), anyString(), any(), any())).thenReturn(1);
        Long[] seen = new Long[1];
        doAnswer(inv -> {
            seen[0] = TenantContext.getCurrentOrgId();
            return null;
        }).when(ingestor).ingest(eq(id), anyString());

        poller.poll();

        assertThat(seen[0]).isEqualTo(7L);
    }

    @Test
    void aJobClaimedByAnotherReplicaIsLeftAlone() {
        UUID id = UUID.randomUUID();
        when(jobs.findClosedNotIngested("DONE", ParseJobPoller.BATCH)).thenReturn(List.of(ref(id, 7L)));
        when(jobs.claimForIngest(eq(id), eq(7L), anyString(), any(), any())).thenReturn(0);

        poller.poll();

        verify(ingestor, never()).ingest(any(), anyString());
    }

    @Test
    void anIngestThatThrowsFailsTheDocumentAndThePassGoesOn() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        when(jobs.findClosedNotIngested("DONE", ParseJobPoller.BATCH)).thenReturn(List.of(ref(bad, 7L), ref(good, 7L)));
        when(jobs.claimForIngest(any(), eq(7L), anyString(), any(), any())).thenReturn(1);
        doThrow(new IllegalStateException("no meta.json")).when(ingestor).ingest(eq(bad), anyString());

        poller.poll();

        verify(ingestor).markFailed(eq(bad), org.mockito.ArgumentMatchers.contains("no meta.json"));
        verify(ingestor).ingest(eq(good), anyString());
    }

    @Test
    void queuedWorkOutsideTheAllowListIsClosedAndAllowedWorkIsNot() {
        when(jobs.findOrganizationsWithQueuedJobs()).thenReturn(List.of(7L, 8L));

        poller.poll();

        verify(jobs).failQueuedForOrganization(eq(8L), any(), anyString());
        verify(jobs, never()).failQueuedForOrganization(eq(7L), any(), anyString());
    }

    @Test
    void aFailingPassDoesNotEscapeTheSchedule() {
        when(jobs.requeueExpiredLeases(any())).thenThrow(new IllegalStateException("database away"));

        poller.poll();

        verify(jobs, never()).findClosedNotIngested(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    private static JobRef ref(UUID id, Long org) {
        return new JobRef() {
            @Override
            public UUID getId() {
                return id;
            }

            @Override
            public Long getOrganizationId() {
                return org;
            }
        };
    }
}
