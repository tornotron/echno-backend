package org.tornotron.echno_backend.compliance.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.compliance.ComplianceGenerationService;
import org.tornotron.echno_backend.organization.Organization;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Accepting work: what it checks before it says yes, and what a second click does.
 */
@ExtendWith(MockitoExtension.class)
class ComplianceGenerationJobServiceTest {

    private static final Long ORG_ID = 7L;
    private static final Long PROJECT_ID = 42L;

    @Mock
    private ComplianceGenerationJobRepository jobRepository;
    @Mock
    private ComplianceGenerationService complianceGenerationService;
    @Mock
    private TenantEntityHelper tenantEntityHelper;
    @Mock
    private TransactionRetryTemplate retryTemplate;

    private ComplianceGenerationJobService service;

    @BeforeEach
    void setUp() {
        service = new ComplianceGenerationJobService(jobRepository, complianceGenerationService,
                new ComplianceJobProperties(), tenantEntityHelper, retryTemplate);

        lenient().doAnswer(invocation -> invocation.getArgument(1, Supplier.class).get())
                .when(retryTemplate).execute(anyString(), any(Supplier.class));
    }

    @Test
    void acceptsAJobRecordingHowMuchWorkItIs() {
        when(complianceGenerationService.validateAndCountCandidateRules(PROJECT_ID, ORG_ID)).thenReturn(25);
        when(complianceGenerationService.batchCount(25)).thenReturn(3);
        when(jobRepository.findActiveForProject(ORG_ID, PROJECT_ID)).thenReturn(Optional.empty());
        when(tenantEntityHelper.resolveCurrentOrganization()).thenReturn(new Organization());
        when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ComplianceGenerationJobService.Accepted accepted = service.submit(PROJECT_ID, ORG_ID);

        assertThat(accepted.created()).isTrue();
        ArgumentCaptor<ComplianceGenerationJob> saved =
                ArgumentCaptor.forClass(ComplianceGenerationJob.class);
        verify(jobRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ComplianceJobStatus.QUEUED);
        assertThat(saved.getValue().getRulesTotal()).isEqualTo(25);
        assertThat(saved.getValue().getBatchesTotal())
                .as("the caller can show a progress bar before the first batch has run")
                .isEqualTo(3);
    }

    /**
     * A precondition the user can fix is answered immediately as the 400 it has always been.
     * Accepting the job and letting a worker discover it a minute later would turn a clear
     * error message into something the user has to go and poll for.
     */
    @Test
    void aPreconditionFailureIsRaisedAtAcceptTimeAndQueuesNothing() {
        when(complianceGenerationService.validateAndCountCandidateRules(PROJECT_ID, ORG_ID))
                .thenThrow(new InvalidRequestException("This project has no type set."));

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.submit(PROJECT_ID, ORG_ID))
                .withMessageContaining("no type set");

        verify(jobRepository, never()).save(any());
    }

    /**
     * Two clicks are one run. Two runs would both spend a minute of inference and then race to
     * create the same inspections, which is the duplicate the inspection-level unique index had
     * to be added to catch.
     */
    @Test
    void aSecondRequestJoinsTheRunAlreadyInFlight() {
        ComplianceGenerationJob running = queuedJob();
        when(complianceGenerationService.validateAndCountCandidateRules(PROJECT_ID, ORG_ID)).thenReturn(25);
        when(jobRepository.findActiveForProject(ORG_ID, PROJECT_ID)).thenReturn(Optional.of(running));

        ComplianceGenerationJobService.Accepted accepted = service.submit(PROJECT_ID, ORG_ID);

        assertThat(accepted.created())
                .as("nothing new was started, so the caller is answered 200 rather than 202")
                .isFalse();
        assertThat(accepted.job().status()).isEqualTo(ComplianceJobStatus.QUEUED);
        verify(jobRepository, never()).save(any());
    }

    /**
     * The read above is a read, so it cannot be the guarantee: two requests arriving together
     * both see no active job and both insert. The partial unique index rejects the loser, and
     * the loser then reads back the winner and hands that to its caller, so both end up
     * watching one run rather than one of them being told about a conflict it cannot act on.
     */
    @Test
    void aRequestThatLosesTheInsertRaceReturnsTheWinnersJob() {
        ComplianceGenerationJob winner = queuedJob();
        when(complianceGenerationService.validateAndCountCandidateRules(PROJECT_ID, ORG_ID)).thenReturn(25);
        when(jobRepository.findActiveForProject(ORG_ID, PROJECT_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(tenantEntityHelper.resolveCurrentOrganization()).thenReturn(new Organization());
        when(jobRepository.save(any())).thenThrow(uniqueViolation());

        ComplianceGenerationJobService.Accepted accepted = service.submit(PROJECT_ID, ORG_ID);

        assertThat(accepted.created()).isFalse();
        assertThat(accepted.job().id()).isEqualTo(winner.getId());
    }

    /**
     * The winner can legitimately have finished between the constraint rejecting our insert and
     * our reading it back. That is not an error, and the newest job for the project is still
     * the one the caller wanted to be told about.
     */
    @Test
    void aRequestThatLosesToAJobThatHasSinceFinishedReturnsThatFinishedJob() {
        ComplianceGenerationJob finished = queuedJob();
        finished.setStatus(ComplianceJobStatus.NOTHING_TO_REPORT);
        when(complianceGenerationService.validateAndCountCandidateRules(PROJECT_ID, ORG_ID)).thenReturn(25);
        when(jobRepository.findActiveForProject(ORG_ID, PROJECT_ID)).thenReturn(Optional.empty());
        when(jobRepository.findLatestForProject(anyLong(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of(finished));
        when(tenantEntityHelper.resolveCurrentOrganization()).thenReturn(new Organization());
        when(jobRepository.save(any())).thenThrow(uniqueViolation());

        ComplianceGenerationJobService.Accepted accepted = service.submit(PROJECT_ID, ORG_ID);

        assertThat(accepted.created()).isFalse();
        assertThat(accepted.job().status()).isEqualTo(ComplianceJobStatus.NOTHING_TO_REPORT);
    }

    /** A failure that is not the duplicate is not swallowed as one. */
    @Test
    void anUnrelatedWriteFailureIsNotMistakenForALostRace() {
        when(complianceGenerationService.validateAndCountCandidateRules(PROJECT_ID, ORG_ID)).thenReturn(25);
        when(jobRepository.findActiveForProject(ORG_ID, PROJECT_ID)).thenReturn(Optional.empty());
        when(tenantEntityHelper.resolveCurrentOrganization()).thenReturn(new Organization());
        when(jobRepository.save(any())).thenThrow(new IllegalStateException("the disk is full"));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> service.submit(PROJECT_ID, ORG_ID))
                .withMessageContaining("disk is full");
    }

    // -----------------------------------------------------------------------------------

    private static ComplianceGenerationJob queuedJob() {
        ComplianceGenerationJob job = new ComplianceGenerationJob();
        job.setId(java.util.UUID.randomUUID());
        job.setProjectId(PROJECT_ID);
        job.setStatus(ComplianceJobStatus.QUEUED);
        job.setRulesTotal(25);
        job.setBatchesTotal(3);
        job.setMaxAttempts(3);
        return job;
    }

    /** What the partial unique index throws up through Spring when it rejects a second job. */
    private static DataIntegrityViolationException uniqueViolation() {
        return new DataIntegrityViolationException(
                "could not execute statement [duplicate key value violates unique constraint "
                        + "\"uk_compliance_job_active\"]",
                new SQLException("duplicate key value violates unique constraint", "23505"));
    }
}
