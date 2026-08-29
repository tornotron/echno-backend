package org.tornotron.echno_backend.compliance.job;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Claiming, and only claiming.
 *
 * <p>The pass is driven with a same-thread executor, so what a poll did is a fact rather than
 * something to wait for. That is the whole reason the worker is a separate bean: the timing
 * lives here and the judgement lives there, and neither test has to carry the other's
 * apparatus.
 */
@ExtendWith(MockitoExtension.class)
class ComplianceGenerationJobDispatcherTest {

    private static final UUID JOB_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER_JOB_ID = UUID.fromString("66666666-7777-8888-9999-000000000000");

    /** Runs the handed-off work where it stands, so a pass is finished when poll() returns. */
    private static final Executor SAME_THREAD = Runnable::run;

    @Mock
    private ComplianceGenerationJobRepository jobRepository;
    @Mock
    private ComplianceGenerationJobWorker worker;
    @Mock
    private TransactionRetryTemplate retryTemplate;

    private ComplianceGenerationJobDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        lenient().doAnswer(invocation -> invocation.getArgument(1, Supplier.class).get())
                .when(retryTemplate).execute(anyString(), any(Supplier.class));

        ComplianceJobProperties properties = new ComplianceJobProperties();
        properties.setMaxInFlight(2);
        dispatcher = new ComplianceGenerationJobDispatcher(
                jobRepository, worker, retryTemplate, properties);
    }

    @AfterEach
    void tearDown() {
        dispatcher.shutdown();
    }

    /**
     * The conditional update is the whole of the mutual exclusion. Losing it means another
     * replica already has the job, and the loser must not go on to run it as well.
     */
    @Test
    void doesNotRunAJobWhoseClaimAnotherReplicaWon() {
        when(jobRepository.findQueuedIds(anyInt())).thenReturn(List.of(JOB_ID));
        when(jobRepository.claim(any(), anyString(), any(), any())).thenReturn(0);

        dispatcher.poll(SAME_THREAD);

        verify(worker, never()).run(any(), anyString());
    }

    @Test
    void runsAJobWhoseClaimItWon() {
        when(jobRepository.findQueuedIds(anyInt())).thenReturn(List.of(JOB_ID));
        when(jobRepository.claim(any(), anyString(), any(), any())).thenReturn(1);

        dispatcher.poll(SAME_THREAD);

        verify(worker).run(JOB_ID, dispatcher.nodeId());
    }

    /**
     * The claim writes this replica's name and a lease ahead of itself. Both are what makes
     * recovery possible: the name is how a superseded worker knows to stop writing, and the
     * lease is how any replica knows the holder is gone.
     */
    @Test
    void theClaimCarriesThisReplicasNameAndALeaseInTheFuture() {
        when(jobRepository.findQueuedIds(anyInt())).thenReturn(List.of(JOB_ID));
        when(jobRepository.claim(any(), anyString(), any(), any())).thenReturn(1);

        dispatcher.poll(SAME_THREAD);

        verify(jobRepository).claim(
                org.mockito.ArgumentMatchers.eq(JOB_ID),
                org.mockito.ArgumentMatchers.eq(dispatcher.nodeId()),
                org.mockito.ArgumentMatchers.argThat(lease -> lease.isAfter(LocalDateTime.now())),
                any());
    }

    /**
     * Slots are held from before the claim until the run ends, so a replica configured for two
     * cannot end up holding three claims it has nowhere to run.
     */
    @Test
    void takesNoMoreWorkThanItHasSlotsFor() {
        UUID third = UUID.randomUUID();
        when(jobRepository.findQueuedIds(anyInt())).thenReturn(List.of(JOB_ID, OTHER_JOB_ID, third));
        when(jobRepository.claim(any(), anyString(), any(), any())).thenReturn(1);

        // Never returns, so both slots stay occupied for the duration of the pass.
        Executor neverFinishing = runnable -> { };
        dispatcher.poll(neverFinishing);

        verify(jobRepository, times(2)).claim(any(), anyString(), any(), any());
    }

    @Test
    void freesTheSlotWhenTheClaimIsLostSoTheNextPassCanUseIt() {
        when(jobRepository.findQueuedIds(anyInt())).thenReturn(List.of(JOB_ID));
        when(jobRepository.claim(any(), anyString(), any(), any())).thenReturn(0);

        dispatcher.poll(SAME_THREAD);
        dispatcher.poll(SAME_THREAD);

        verify(jobRepository, times(2)).findQueuedIds(2);
    }

    /** Recovery runs before new work is taken, so a freed job is available in the same pass. */
    @Test
    void returnsJobsWithAnExpiredLeaseToTheQueue() {
        when(jobRepository.requeueExpiredLeases(anyString(), any())).thenReturn(1);
        when(jobRepository.findQueuedIds(anyInt())).thenReturn(List.of());

        dispatcher.poll(SAME_THREAD);

        verify(jobRepository).requeueExpiredLeases(anyString(), any());
        verify(jobRepository).failExpiredLeasesOutOfAttempts(anyString(), any());
    }

    /**
     * Spring stops rescheduling a {@code @Scheduled} method that throws, and a queue that
     * silently stops draining is the failure this whole change exists to remove. So a bad pass
     * has to be survivable, which means it has to be swallowed here.
     */
    @Test
    void aFailedPassDoesNotEscapeAndCancelTheSchedule() {
        when(jobRepository.requeueExpiredLeases(anyString(), any()))
                .thenThrow(new IllegalStateException("the database is unreachable"));

        assertThatCode(() -> dispatcher.poll(SAME_THREAD)).doesNotThrowAnyException();

        // And the next pass still happens. Restubbed with doReturn because when() would call the
        // mock, and this one is currently set to throw.
        doReturn(0).when(jobRepository).requeueExpiredLeases(anyString(), any());
        doReturn(List.of()).when(jobRepository).findQueuedIds(anyInt());
        dispatcher.poll(SAME_THREAD);
        verify(jobRepository, times(2)).requeueExpiredLeases(anyString(), any());
    }

    /**
     * A worker that throws on its way out must not take the slot with it, or a replica would
     * quietly lose capacity one failure at a time until it stopped taking work at all.
     */
    @Test
    void aWorkerThatThrowsStillGivesItsSlotBack() {
        when(jobRepository.findQueuedIds(anyInt())).thenReturn(List.of(JOB_ID));
        when(jobRepository.claim(any(), anyString(), any(), any())).thenReturn(1);
        doAnswer(invocation -> {
            throw new IllegalStateException("something went badly wrong");
        }).when(worker).run(any(), anyString());

        dispatcher.poll(SAME_THREAD);
        dispatcher.poll(SAME_THREAD);

        verify(jobRepository, times(2)).findQueuedIds(2);
    }
}
