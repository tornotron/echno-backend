package org.tornotron.echno_backend.compliance;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.tornotron.echno_backend.common.exception.TransactionRetriesExhaustedException;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;
import org.tornotron.echno_backend.compliance.ai.ComplianceSuggestion;
import org.tornotron.echno_backend.compliance.ai.OpenAiCompatibleComplianceService;
import org.tornotron.echno_backend.compliance.domain.ComplianceRule;
import org.tornotron.echno_backend.compliance.repository.ComplianceRuleRepository;
import org.tornotron.echno_backend.inspection.ComplianceRiskLevel;
import org.tornotron.echno_backend.inspection.domain.Inspection;
import org.tornotron.echno_backend.inspection.dtos.InspectionDto;
import org.tornotron.echno_backend.inspection.mapper.InspectionMapper;
import org.tornotron.echno_backend.inspection.repositories.InspectionRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.project.enums.ProjectType;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What happens when two compliance generations for the same project overlap.
 *
 * <p>The idempotency check is a plain read, so it cannot be the guarantee: both runs see
 * "no such row" and both insert. Nor does {@code SERIALIZABLE} catch it, because the two
 * transactions do not conflict; each inserts a different row with its own id and its own
 * inspection number, so there is nothing for the database to detect. The guarantee is the
 * unique index from changelog {@code 060}, and what these tests cover is the half that
 * lives in code: the loser's rejected insert has to become a skip rather than a 500.
 *
 * <p>The retry template is the real one here rather than a mock, because the cooperation
 * between the constraint and the restart is the behaviour under test. Everything around it
 * is a mock, so no database, no context and no Testcontainer is involved.
 */
class ComplianceGenerationConcurrencyTest {

    private static final Long PROJECT_ID = 42L;
    private static final Long ORG_ID = 7L;
    private static final String RULE_CODE = "TN-BPA";

    private ProjectRepository projectRepository;
    private ComplianceRuleRepository ruleRepository;
    private OpenAiCompatibleComplianceService complianceAiService;
    private InspectionRepository inspectionRepository;
    private EntryNumberGenerator numberGen;
    private TenantEntityHelper tenantEntityHelper;
    private InspectionMapper inspectionMapper;
    private ComplianceGenerationService service;

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectRepository.class);
        ruleRepository = mock(ComplianceRuleRepository.class);
        complianceAiService = mock(OpenAiCompatibleComplianceService.class);
        inspectionRepository = mock(InspectionRepository.class);
        numberGen = mock(EntryNumberGenerator.class);
        tenantEntityHelper = mock(TenantEntityHelper.class);
        inspectionMapper = mock(InspectionMapper.class);

        // Backoff at zero so the restarts cost the build no wall-clock time.
        TransactionRetryTemplate retryTemplate = new TransactionRetryTemplate(
                new TransactionalWorkRunner(), new SimpleMeterRegistry(), 4, 0L, 0L);

        service = new ComplianceGenerationService(
                projectRepository, ruleRepository, complianceAiService, inspectionRepository,
                numberGen, tenantEntityHelper, inspectionMapper, retryTemplate);

        Project project = new Project();
        project.setProjectName("Race Test");
        project.setProjectType(ProjectType.RESIDENTIAL);
        project.setProjectState("Tamil Nadu");
        when(projectRepository.findByIdAndOrganization_Id(PROJECT_ID, ORG_ID))
                .thenReturn(Optional.of(project));
        when(ruleRepository.findByStateIgnoreCaseAndProjectTypeAndActiveTrue(anyString(), any()))
                .thenReturn(List.of(rule()));
        when(complianceAiService.suggestCompliances(any(Project.class), anyString(), any(), any()))
                .thenReturn(List.of(suggestion()));
        when(tenantEntityHelper.resolveCurrentOrganization()).thenReturn(new Organization());
        when(numberGen.next(anyString())).thenReturn("INSP-0001");
    }

    private static ComplianceRule rule() {
        ComplianceRule rule = new ComplianceRule();
        rule.setCode(RULE_CODE);
        rule.setName("Building Plan Approval");
        rule.setPhase(CompliancePhase.PRE_CONSTRUCTION);
        rule.setDefaultRiskLevel(ComplianceRiskLevel.CRITICAL);
        rule.setState("Tamil Nadu");
        rule.setProjectType(ProjectType.RESIDENTIAL);
        return rule;
    }

    private static ComplianceSuggestion suggestion() {
        return new ComplianceSuggestion(RULE_CODE, true, "critical", List.of("Apply"),
                "Required.", "pre-construction");
    }

    /** A unique-index rejection in the shape Spring hands to application code. */
    private static DataIntegrityViolationException duplicateComplianceRow() {
        return new DataIntegrityViolationException(
                "could not execute statement [duplicate key value violates unique constraint "
                        + "\"uk_inspections_org_project_compliance_rule\"]",
                new SQLException("duplicate key value violates unique constraint", "23505"));
    }

    /**
     * The race, end to end. The first attempt's existence check passes, the concurrent run
     * commits its row in the gap, and this run's insert is rejected by the unique index. The
     * write phase restarts, the second attempt's check now reads the committed row and skips
     * it, and the request answers with what this run genuinely created (nothing).
     *
     * <p>Without the retry the rejection travels straight out: a duplicate the user never
     * asked to create, reported to them as a failure.
     */
    @Test
    void losingTheRaceToAConcurrentRunSkipsTheRuleInsteadOfFailingTheRequest() {
        AtomicInteger existsCalls = new AtomicInteger();
        when(inspectionRepository.existsByProjectIdAndComplianceRuleRefAndOrganization_Id(
                PROJECT_ID, RULE_CODE, ORG_ID))
                // First attempt: nothing there yet. Second attempt: the winner's row is
                // committed and visible, which is what makes the restart converge.
                .thenAnswer(invocation -> existsCalls.incrementAndGet() > 1);
        when(inspectionRepository.save(any(Inspection.class))).thenThrow(duplicateComplianceRow());

        List<InspectionDto> created = service.generateForProject(PROJECT_ID, ORG_ID);

        assertThat(created).isEmpty();
        assertThat(existsCalls).hasValue(2);
        verify(inspectionRepository, times(1)).save(any(Inspection.class));
    }

    /**
     * The ordinary case has to stay ordinary: no conflict, one attempt, one row, no restart.
     */
    @Test
    void createsTheInspectionWhenNothingRacesIt() {
        when(inspectionRepository.existsByProjectIdAndComplianceRuleRefAndOrganization_Id(
                PROJECT_ID, RULE_CODE, ORG_ID)).thenReturn(false);
        Inspection saved = new Inspection();
        when(inspectionRepository.save(any(Inspection.class))).thenReturn(saved);

        // The mapper's own output is not what is under test here, only that the row was
        // created and mapped once, so its default null return is left as it is.
        assertThat(service.generateForProject(PROJECT_ID, ORG_ID)).hasSize(1);

        verify(inspectionRepository, times(1)).save(any(Inspection.class));
        verify(inspectionMapper, times(1)).toDto(saved);
    }

    /**
     * A rule that already has an inspection is skipped without ever reaching the constraint,
     * which is what keeps a re-run cheap: the index is the guarantee, the check is the fast
     * path.
     */
    @Test
    void skipsARuleThatAlreadyHasAnInspection() {
        when(inspectionRepository.existsByProjectIdAndComplianceRuleRefAndOrganization_Id(
                PROJECT_ID, RULE_CODE, ORG_ID)).thenReturn(true);

        assertThat(service.generateForProject(PROJECT_ID, ORG_ID)).isEmpty();

        verify(inspectionRepository, times(0)).save(any(Inspection.class));
    }

    /**
     * The restart is bounded. A conflict that a fresh read never resolves gives up as a
     * conflict (409) rather than retrying forever or surfacing as an unknown 500.
     */
    @Test
    void givesUpAsAConflictWhenTheDuplicateNeverResolves() {
        when(inspectionRepository.existsByProjectIdAndComplianceRuleRefAndOrganization_Id(
                PROJECT_ID, RULE_CODE, ORG_ID)).thenReturn(false);
        when(inspectionRepository.save(any(Inspection.class))).thenThrow(duplicateComplianceRow());

        assertThatExceptionOfType(TransactionRetriesExhaustedException.class)
                .isThrownBy(() -> service.generateForProject(PROJECT_ID, ORG_ID));

        verify(inspectionRepository, times(4)).save(any(Inspection.class));
    }

    /**
     * Only a unique violation is nominated as retryable. Anything else the write can fail
     * with is a real fault and must surface as itself on the first attempt, not be retried
     * three more times and then reported as a concurrency conflict.
     */
    @Test
    void doesNotRestartOnAnIntegrityFailureThatIsNotADuplicate() {
        DataIntegrityViolationException notNull = new DataIntegrityViolationException(
                "null value in column violates not-null constraint",
                new SQLException("null value in column", "23502"));
        when(inspectionRepository.existsByProjectIdAndComplianceRuleRefAndOrganization_Id(
                PROJECT_ID, RULE_CODE, ORG_ID)).thenReturn(false);
        when(inspectionRepository.save(any(Inspection.class))).thenThrow(notNull);

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> service.generateForProject(PROJECT_ID, ORG_ID))
                .isSameAs(notNull);

        verify(inspectionRepository, times(1)).save(any(Inspection.class));
    }

    /**
     * The model call sits between the two transactions and must not be repeated by a restart
     * of the write phase. It is the slow part of the run and, unlike the write, it
     * is not free to run again.
     */
    @Test
    void restartingTheWritePhaseDoesNotCallTheModelAgain() {
        AtomicInteger existsCalls = new AtomicInteger();
        when(inspectionRepository.existsByProjectIdAndComplianceRuleRefAndOrganization_Id(
                PROJECT_ID, RULE_CODE, ORG_ID))
                .thenAnswer(invocation -> existsCalls.incrementAndGet() > 1);
        when(inspectionRepository.save(any(Inspection.class))).thenThrow(duplicateComplianceRow());

        service.generateForProject(PROJECT_ID, ORG_ID);

        verify(complianceAiService, times(1))
                .suggestCompliances(any(Project.class), eq("Tamil Nadu"), any(), any());
    }
}
