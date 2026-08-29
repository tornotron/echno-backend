package org.tornotron.echno_backend.compliance.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.exception.ComplianceAiException;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.compliance.ComplianceGenerationProgress;
import org.tornotron.echno_backend.compliance.ComplianceGenerationService;
import org.tornotron.echno_backend.inspection.dtos.InspectionDto;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a run means, decided without a thread pool anywhere near it.
 *
 * <p>The five terminal outcomes are the point. A job that finished having created nothing is
 * not the same thing as a job that fell over, and before the truncation checks landed those
 * two arrived at the user as the same empty list. Moving generation into the background is
 * exactly the sort of change that would quietly put that ambiguity back, one layer further
 * from anyone who could notice it, so it is pinned here.
 */
@ExtendWith(MockitoExtension.class)
class ComplianceGenerationJobWorkerTest {

    private static final UUID JOB_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Long ORG_ID = 7L;
    private static final Long PROJECT_ID = 42L;
    private static final String NODE = "replica-a";

    @Mock
    private ComplianceGenerationJobRepository jobRepository;
    @Mock
    private ComplianceGenerationService complianceGenerationService;
    @Mock
    private TenantScopedJobRunner tenantScopedJobRunner;
    @Mock
    private TransactionRetryTemplate retryTemplate;

    private ComplianceGenerationJobWorker worker;
    private ComplianceGenerationJob row;

    @BeforeEach
    void setUp() {
        ComplianceJobProperties properties = new ComplianceJobProperties();
        worker = new ComplianceGenerationJobWorker(
                jobRepository, complianceGenerationService, tenantScopedJobRunner,
                retryTemplate, properties);

        // The transaction boundary is not what these tests are about, so the template runs the
        // work where it stands.
        lenient().doAnswer(invocation -> invocation.getArgument(1, Supplier.class).get())
                .when(retryTemplate).execute(anyString(), any(Supplier.class));
        lenient().doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(retryTemplate).executeWithoutResult(anyString(), any(Runnable.class));

        lenient().doAnswer(invocation -> {
            TenantContext.setCurrentOrgId(invocation.getArgument(0));
            try {
                invocation.getArgument(1, Runnable.class).run();
            } finally {
                TenantContext.clear();
            }
            return null;
        }).when(tenantScopedJobRunner).runForTenant(anyLong(), any(Runnable.class));

        row = new ComplianceGenerationJob();
        row.setStatus(ComplianceJobStatus.RUNNING);
        row.setClaimedBy(NODE);
        row.setProjectId(PROJECT_ID);
        row.setAttempt(1);
        lenient().when(jobRepository.findDispatchRow(JOB_ID)).thenReturn(Optional.of(dispatchRow(1, 3)));
        lenient().when(jobRepository.findByIdScoped(JOB_ID, ORG_ID)).thenReturn(Optional.of(row));
    }

    @Test
    void aRunThatCreatedComplianceIsSucceeded() {
        when(complianceGenerationService.generateForProject(eq(PROJECT_ID), eq(ORG_ID), any()))
                .thenReturn(createdRows(2));

        worker.run(JOB_ID, NODE);

        assertThat(row.getStatus()).isEqualTo(ComplianceJobStatus.SUCCEEDED);
        assertThat(row.getCreatedCount()).isEqualTo(2);
        assertThat(row.getErrorMessage()).isNull();
        assertThat(row.getFinishedAt()).isNotNull();
    }

    /**
     * The distinction the whole status set exists for. Nothing was created and nothing went
     * wrong, and calling that "succeeded with zero" would leave the user unable to tell it from
     * a run whose answer was thrown away.
     */
    @Test
    void aRunThatCoveredEveryRuleAndCreatedNothingIsNothingToReport() {
        when(complianceGenerationService.generateForProject(eq(PROJECT_ID), eq(ORG_ID), any()))
                .thenReturn(List.of());

        worker.run(JOB_ID, NODE);

        assertThat(row.getStatus()).isEqualTo(ComplianceJobStatus.NOTHING_TO_REPORT);
        assertThat(row.getCreatedCount()).isZero();
        assertThat(row.getErrorMessage()).isNull();
    }

    /**
     * And the other half of it. A model answer cut short is a failure, not an empty result, and
     * it must not be recorded as one.
     */
    @Test
    void aTruncatedModelAnswerIsFailedAndNotAnEmptySuccess() {
        row.setStatus(ComplianceJobStatus.RUNNING);
        row.setAttempt(3);
        when(jobRepository.findDispatchRow(JOB_ID)).thenReturn(Optional.of(dispatchRow(3, 3)));
        when(complianceGenerationService.generateForProject(eq(PROJECT_ID), eq(ORG_ID), any()))
                .thenThrow(new ComplianceAiException(
                        "The compliance AI response was cut short by its token limit"));

        worker.run(JOB_ID, NODE);

        assertThat(row.getStatus()).isEqualTo(ComplianceJobStatus.FAILED);
        assertThat(row.getCreatedCount()).isZero();
        assertThat(row.getErrorMessage()).contains("cut short");
    }

    @Test
    void aFailureWithAttemptsLeftGoesBackToTheQueueWithTheReasonOnIt() {
        when(jobRepository.findDispatchRow(JOB_ID)).thenReturn(Optional.of(dispatchRow(1, 3)));
        when(complianceGenerationService.generateForProject(eq(PROJECT_ID), eq(ORG_ID), any()))
                .thenThrow(new ComplianceAiException("the endpoint returned 503"));

        worker.run(JOB_ID, NODE);

        assertThat(row.getStatus()).isEqualTo(ComplianceJobStatus.QUEUED);
        assertThat(row.getClaimedBy()).isNull();
        assertThat(row.getLeaseExpiresAt()).isNull();
        assertThat(row.getErrorMessage())
                .as("a caller polling through a retry should be told what is going wrong")
                .contains("Attempt 1 of 3")
                .contains("503");
        assertThat(row.getFinishedAt()).isNull();
    }

    /**
     * Progress is reset on the way back to the queue. Leaving it would show a job that is
     * waiting to start over as though it were two batches into a run.
     */
    @Test
    void requeueingResetsTheProgressCounters() {
        row.setBatchesDone(2);
        row.setRulesAssessed(20);
        when(complianceGenerationService.generateForProject(eq(PROJECT_ID), eq(ORG_ID), any()))
                .thenThrow(new ComplianceAiException("the endpoint returned 503"));

        worker.run(JOB_ID, NODE);

        assertThat(row.getBatchesDone()).isZero();
        assertThat(row.getRulesAssessed()).isZero();
    }

    /**
     * A precondition failure is decided from the project row and the configuration, so every
     * further attempt would read the same rows and fail identically. Retrying it would only
     * delay telling the user what to fix.
     */
    @Test
    void aPreconditionFailureIsTerminalWithoutSpendingTheRemainingAttempts() {
        when(complianceGenerationService.generateForProject(eq(PROJECT_ID), eq(ORG_ID), any()))
                .thenThrow(new InvalidRequestException("This project has no type set."));

        worker.run(JOB_ID, NODE);

        assertThat(row.getStatus()).isEqualTo(ComplianceJobStatus.FAILED);
        assertThat(row.getErrorMessage()).contains("no type set");
    }

    @Test
    void theLastAttemptFailsRatherThanQueueingForever() {
        row.setAttempt(3);
        when(jobRepository.findDispatchRow(JOB_ID)).thenReturn(Optional.of(dispatchRow(3, 3)));
        when(complianceGenerationService.generateForProject(eq(PROJECT_ID), eq(ORG_ID), any()))
                .thenThrow(new ComplianceAiException("the endpoint returned 503"));

        worker.run(JOB_ID, NODE);

        assertThat(row.getStatus()).isEqualTo(ComplianceJobStatus.FAILED);
        assertThat(row.getFinishedAt()).isNotNull();
    }

    /**
     * The work is tenant-scoped from an id read off the row, never from whatever happened to
     * be on the pooled thread. Both isolation mechanisms fail open on a missing context, so a
     * worker that skipped this would read every organization's rows and look healthy doing it.
     */
    @Test
    void runsTheWorkUnderTheTenantNamedOnTheJobRow() {
        AtomicReference<Long> tenantDuringWork = new AtomicReference<>();
        when(complianceGenerationService.generateForProject(eq(PROJECT_ID), eq(ORG_ID), any()))
                .thenAnswer(invocation -> {
                    tenantDuringWork.set(TenantContext.getCurrentOrgId());
                    return List.of();
                });

        worker.run(JOB_ID, NODE);

        verify(tenantScopedJobRunner).runForTenant(eq(ORG_ID), any(Runnable.class));
        assertThat(tenantDuringWork.get()).isEqualTo(ORG_ID);
    }

    /**
     * A worker whose lease expired has had its job taken by another replica. Writing an outcome
     * now would overwrite what the new owner is doing with a result from a run nobody is
     * watching any more.
     */
    @Test
    void doesNotWriteAnOutcomeOntoAJobItNoLongerHolds() {
        row.setStatus(ComplianceJobStatus.RUNNING);
        row.setClaimedBy("replica-b");
        when(complianceGenerationService.generateForProject(eq(PROJECT_ID), eq(ORG_ID), any()))
                .thenReturn(createdRows(1));

        worker.run(JOB_ID, NODE);

        assertThat(row.getStatus()).isEqualTo(ComplianceJobStatus.RUNNING);
        assertThat(row.getCreatedCount()).isZero();
        verify(jobRepository, never()).save(any());
    }

    /**
     * Progress and the lease move together, and both are conditional on still holding the
     * claim, so a worker that has been superseded cannot rewind the new owner's progress.
     */
    @Test
    void progressWritesCarryTheLeaseAndTheClaimHolder() {
        when(complianceGenerationService.generateForProject(eq(PROJECT_ID), eq(ORG_ID), any()))
                .thenAnswer(invocation -> {
                    invocation.getArgument(2, ComplianceGenerationProgress.class)
                            .batchCompleted(2, 3, 20, 25);
                    return List.of();
                });
        when(jobRepository.recordProgress(any(), anyString(), anyInt(), anyInt(), anyInt(), any(), any()))
                .thenReturn(1);

        worker.run(JOB_ID, NODE);

        ArgumentCaptor<LocalDateTime> lease = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(jobRepository).recordProgress(eq(JOB_ID), eq(NODE), eq(1), eq(2), eq(20),
                lease.capture(), any(LocalDateTime.class));
        assertThat(lease.getValue())
                .as("finishing a batch is the evidence this worker is alive, so it extends the claim")
                .isAfter(LocalDateTime.now());
    }

    /**
     * The preconditions are checked again on the worker, because the row outlives the
     * configuration it was accepted under. The sharpest case is the AI key disappearing in a
     * restart: the generation call answers an unconfigured service with an empty list, and a
     * worker that reached it would record NOTHING_TO_REPORT for a run that asked nothing.
     */
    @Test
    void aPreconditionThatLapsedBetweenAcceptAndRunFailsTheJobRatherThanReportingNothing() {
        when(complianceGenerationService.validateAndCountCandidateRules(PROJECT_ID, ORG_ID))
                .thenThrow(new InvalidRequestException(
                        "The compliance AI service is not configured, so suggestions cannot be "
                                + "generated. Set the compliance AI key and try again."));

        worker.run(JOB_ID, NODE);

        assertThat(row.getStatus()).isEqualTo(ComplianceJobStatus.FAILED);
        assertThat(row.getErrorMessage()).contains("not configured");
        verify(complianceGenerationService, never()).generateForProject(anyLong(), anyLong(), any());
    }

    /**
     * The replica name alone does not identify a claim. A job whose lease lapsed can be
     * requeued and re-claimed by the same replica, and then the row carries this worker's own
     * name with a later attempt. The superseded run must recognise that the row is no longer
     * its own, exactly as it would had another replica taken it.
     */
    @Test
    void doesNotWriteAnOutcomeOntoAJobItsOwnReplicaReclaimed() {
        row.setStatus(ComplianceJobStatus.RUNNING);
        row.setClaimedBy(NODE);
        row.setAttempt(2);
        when(jobRepository.findDispatchRow(JOB_ID)).thenReturn(Optional.of(dispatchRow(1, 3)));
        when(complianceGenerationService.generateForProject(eq(PROJECT_ID), eq(ORG_ID), any()))
                .thenReturn(createdRows(1));

        worker.run(JOB_ID, NODE);

        assertThat(row.getStatus()).isEqualTo(ComplianceJobStatus.RUNNING);
        assertThat(row.getCreatedCount()).isZero();
        verify(jobRepository, never()).save(any());
    }

    @Test
    void aJobThatVanishedBetweenTheClaimAndTheRunIsLeftAlone() {
        when(jobRepository.findDispatchRow(JOB_ID)).thenReturn(Optional.empty());

        worker.run(JOB_ID, NODE);

        verify(complianceGenerationService, never()).generateForProject(anyLong(), anyLong(), any());
        verify(jobRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------------------

    private static ComplianceGenerationJobRepository.DispatchRow dispatchRow(int attempt, int maxAttempts) {
        return new ComplianceGenerationJobRepository.DispatchRow() {
            @Override
            public Long getOrganizationId() {
                return ORG_ID;
            }

            @Override
            public Long getProjectId() {
                return PROJECT_ID;
            }

            @Override
            public int getAttempt() {
                return attempt;
            }

            @Override
            public int getMaxAttempts() {
                return maxAttempts;
            }

            @Override
            public int getRulesTotal() {
                return 25;
            }

            @Override
            public int getBatchesTotal() {
                return 3;
            }
        };
    }

    /**
     * A result list of the given length. The worker reads nothing but the size, and an
     * InspectionDto carries thirty-seven components, so filling them in would be thirty-seven
     * pieces of noise standing in for the number two.
     */
    private static List<InspectionDto> createdRows(int count) {
        return Arrays.asList(new InspectionDto[count]);
    }
}
