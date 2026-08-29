package org.tornotron.echno_backend.compliance.sweep;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.compliance.job.ComplianceGenerationJobDto;
import org.tornotron.echno_backend.compliance.job.ComplianceGenerationJobRepository;
import org.tornotron.echno_backend.compliance.job.ComplianceGenerationJobService;
import org.tornotron.echno_backend.compliance.repository.ComplianceRuleRepository;
import org.tornotron.echno_backend.project.enums.ProjectType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the sweep decides, and what it refuses to decide.
 *
 * <p>The comparison is the whole of this class's behaviour, so most of these tests are one
 * project and two timestamps. The rest are the bounds: the per-run cap, and the two ways a
 * single project can go wrong without taking the pass down with it.
 */
@ExtendWith(MockitoExtension.class)
class ComplianceRuleSweepTest {

    private static final Long ORG_ID = 7L;
    private static final LocalDateTime RULE_CHANGED = LocalDateTime.of(2026, 8, 21, 9, 30);

    @Mock
    private ComplianceRuleRepository ruleRepository;
    @Mock
    private ComplianceGenerationJobRepository jobRepository;
    @Mock
    private ComplianceGenerationJobService jobService;
    @Mock
    private TransactionRetryTemplate retryTemplate;

    private ComplianceSweepProperties properties;
    private ComplianceRuleSweep sweep;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        properties = new ComplianceSweepProperties();
        sweep = new ComplianceRuleSweep(ruleRepository, jobRepository, jobService,
                new TenantScopedJobRunner(), retryTemplate, properties);

        lenient().when(retryTemplate.execute(anyString(), any(Supplier.class)))
                .thenAnswer(call -> ((Supplier<Object>) call.getArgument(1)).get());
    }

    // -------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------

    /** One jurisdiction whose newest rule came into force at {@link #RULE_CHANGED}. */
    private void catalogueChangedAt(LocalDateTime effectiveFrom) {
        when(ruleRepository.findNewestEffectiveFromByJurisdiction())
                .thenReturn(List.of(jurisdiction("Tamil Nadu", ProjectType.RESIDENTIAL, effectiveFrom)));
    }

    private ComplianceRuleRepository.JurisdictionChange jurisdiction(String state,
                                                                    ProjectType type,
                                                                    LocalDateTime effectiveFrom) {
        return new ComplianceRuleRepository.JurisdictionChange() {
            @Override
            public String getState() {
                return state;
            }

            @Override
            public ProjectType getProjectType() {
                return type;
            }

            @Override
            public LocalDateTime getNewestEffectiveFrom() {
                return effectiveFrom;
            }
        };
    }

    private ComplianceGenerationJobRepository.SweepCandidate project(long id,
                                                                    String state,
                                                                    LocalDateTime lastAssessedAt) {
        return new ComplianceGenerationJobRepository.SweepCandidate() {
            @Override
            public Long getProjectId() {
                return id;
            }

            @Override
            public Long getOrganizationId() {
                return ORG_ID;
            }

            @Override
            public String getProjectState() {
                return state;
            }

            @Override
            public String getProjectAddress() {
                return "Some Street";
            }

            @Override
            public String getProjectType() {
                return ProjectType.RESIDENTIAL.name();
            }

            @Override
            public LocalDateTime getLastAssessedAt() {
                return lastAssessedAt;
            }
        };
    }

    private void candidates(ComplianceGenerationJobRepository.SweepCandidate... rows) {
        when(jobRepository.findSweepCandidates(anyInt())).thenReturn(List.of(rows));
    }

    private void submitCreates(boolean created) {
        when(jobService.submit(anyLong(), anyLong()))
                .thenReturn(new ComplianceGenerationJobService.Accepted(
                        (ComplianceGenerationJobDto) null, created));
    }

    // -------------------------------------------------------------------------------
    // The comparison
    // -------------------------------------------------------------------------------

    /**
     * The backlog case, and the reason the issue was filed: a project approved before any rules
     * covered it has never been assessed, so it is stale whatever the timestamps say.
     */
    @Test
    void queuesAProjectThatWasNeverAssessed() {
        catalogueChangedAt(RULE_CHANGED);
        candidates(project(1L, "Tamil Nadu", null));
        submitCreates(true);

        sweep.runPass();

        verify(jobService).submit(1L, ORG_ID);
    }

    @Test
    void queuesAProjectAssessedBeforeTheNewestRuleChange() {
        catalogueChangedAt(RULE_CHANGED);
        candidates(project(1L, "Tamil Nadu", RULE_CHANGED.minusDays(1)));
        submitCreates(true);

        sweep.runPass();

        verify(jobService).submit(1L, ORG_ID);
    }

    /**
     * The case that keeps the sweep cheap. A project already assessed against everything in
     * force costs nothing, every night, for ever.
     */
    @Test
    void leavesAProjectAssessedAfterTheNewestRuleChangeAlone() {
        catalogueChangedAt(RULE_CHANGED);
        candidates(project(1L, "Tamil Nadu", RULE_CHANGED.plusDays(1)));

        sweep.runPass();

        verify(jobService, never()).submit(anyLong(), anyLong());
    }

    /**
     * An assessment at exactly the moment the rule came into force counts as having seen it.
     * The boundary has to fall one way or the other, and falling this way means a rule and an
     * assessment written in the same instant do not put the project back in the queue every
     * night with nothing to add.
     */
    @Test
    void treatsAnAssessmentAtTheSameInstantAsUpToDate() {
        catalogueChangedAt(RULE_CHANGED);
        candidates(project(1L, "Tamil Nadu", RULE_CHANGED));

        sweep.runPass();

        verify(jobService, never()).submit(anyLong(), anyLong());
    }

    /** State is matched the way generation matches it, so casing cannot hide a rule change. */
    @Test
    void matchesTheJurisdictionCaseInsensitively() {
        catalogueChangedAt(RULE_CHANGED);
        candidates(project(1L, "TAMIL NADU", null));
        submitCreates(true);

        sweep.runPass();

        verify(jobService).submit(1L, ORG_ID);
    }

    // -------------------------------------------------------------------------------
    // The bounds
    // -------------------------------------------------------------------------------

    /**
     * The spend bound. Without it one pass over a large estate queues every stale project at
     * once, which is a bill rather than a bug, and is the reason the cap is configuration.
     */
    @Test
    void stopsAtThePerRunCap() {
        properties.setMaxProjectsPerRun(2);
        catalogueChangedAt(RULE_CHANGED);
        candidates(project(1L, "Tamil Nadu", null),
                project(2L, "Tamil Nadu", null),
                project(3L, "Tamil Nadu", null),
                project(4L, "Tamil Nadu", null));
        submitCreates(true);

        sweep.runPass();

        verify(jobService).submit(1L, ORG_ID);
        verify(jobService).submit(2L, ORG_ID);
        verify(jobService, never()).submit(3L, ORG_ID);
        verify(jobService, never()).submit(4L, ORG_ID);
    }

    /**
     * A run already in flight is not a second run, and it does not spend one of the pass's
     * slots either: the project is already being dealt with.
     */
    @Test
    void doesNotCountAProjectAlreadyRunningAgainstTheCap() {
        properties.setMaxProjectsPerRun(1);
        catalogueChangedAt(RULE_CHANGED);
        candidates(project(1L, "Tamil Nadu", null), project(2L, "Tamil Nadu", null));
        when(jobService.submit(1L, ORG_ID)).thenReturn(
                new ComplianceGenerationJobService.Accepted((ComplianceGenerationJobDto) null, false));
        when(jobService.submit(2L, ORG_ID)).thenReturn(
                new ComplianceGenerationJobService.Accepted((ComplianceGenerationJobDto) null, true));

        sweep.runPass();

        verify(jobService).submit(1L, ORG_ID);
        verify(jobService).submit(2L, ORG_ID);
    }

    // -------------------------------------------------------------------------------
    // One bad project must not cost the others their night
    // -------------------------------------------------------------------------------

    /**
     * A project whose state cannot be resolved is skipped before anything is submitted. There
     * is nothing to retry: generation refuses it at the precondition, so queueing it would
     * produce a failed job every night and no compliances ever.
     */
    @Test
    void skipsAProjectWhoseStateCannotBeResolved() {
        catalogueChangedAt(RULE_CHANGED);
        candidates(project(1L, null, null));

        sweep.runPass();

        verify(jobService, never()).submit(anyLong(), anyLong());
    }

    /**
     * A precondition failure on one project is expected, not exceptional, and the pass has to
     * carry on to the next one. Without the catch the first such project ends the pass, and
     * because candidates come back oldest-assessed first it would be the same project every
     * night, blocking everything behind it for ever.
     */
    @Test
    void carriesOnAfterOneProjectIsRefused() {
        catalogueChangedAt(RULE_CHANGED);
        candidates(project(1L, "Tamil Nadu", null), project(2L, "Tamil Nadu", null));
        when(jobService.submit(1L, ORG_ID))
                .thenThrow(new InvalidRequestException("No compliance rules are registered yet."));
        when(jobService.submit(2L, ORG_ID)).thenReturn(
                new ComplianceGenerationJobService.Accepted((ComplianceGenerationJobDto) null, true));

        sweep.runPass();

        verify(jobService).submit(2L, ORG_ID);
    }

    /**
     * The sweep ships switched off, and that is a decision rather than an oversight: turning it
     * on spends inference money, and which catalogue changes justify that is a product call.
     * A default that flipped to true in a later edit would start spending on every environment
     * that upgraded, so it is worth a test rather than a comment.
     */
    @Test
    void isOffByDefault() {
        ComplianceSweepProperties defaults = new ComplianceSweepProperties();

        assertThat(defaults.isEnabled()).isFalse();
        assertThat(defaults.getCron()).isEqualTo("0 0 2 * * *");
        assertThat(defaults.getZone()).isEqualTo("UTC");
        assertThat(defaults.getMaxProjectsPerRun()).isEqualTo(25);
        assertThat(defaults.getScanLimit()).isEqualTo(500);
    }

    /** An empty catalogue is not an occasion to queue anything; there is nothing to compare to. */
    @Test
    void doesNothingWhenNoRulesAreActive() {
        when(ruleRepository.findNewestEffectiveFromByJurisdiction()).thenReturn(List.of());

        sweep.runPass();

        verify(jobRepository, never()).findSweepCandidates(anyInt());
        verify(jobService, never()).submit(anyLong(), anyLong());
    }
}
