package org.tornotron.echno_backend.compliance;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.numbering.EntryNumberGenerator;
import org.tornotron.echno_backend.common.retry.SqlStateDetector;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;
import org.tornotron.echno_backend.compliance.ai.OpenAiCompatibleComplianceService;
import org.tornotron.echno_backend.compliance.job.ComplianceGenerationJobRepository;
import org.tornotron.echno_backend.compliance.job.ComplianceJobStatus;
import org.tornotron.echno_backend.compliance.ai.ComplianceSuggestion;
import org.tornotron.echno_backend.inspection.ComplianceRiskLevel;
import org.tornotron.echno_backend.inspection.InspectionOrigin;
import org.tornotron.echno_backend.inspection.InspectionStatus;
import org.tornotron.echno_backend.inspection.InspectionType;
import org.tornotron.echno_backend.inspection.dtos.InspectionDto;
import org.tornotron.echno_backend.inspection.mapper.InspectionMapperImpl;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.enums.ProjectCreationStatus;
import org.tornotron.echno_backend.project.enums.ProjectType;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Exercises compliance generation against a real CockroachDB with the migration-seeded
 * {@code compliance_rules} in place. The AI call is stubbed (never the real API): the
 * stub marks two of the seeded Tamil Nadu residential rules as applicable, and the test
 * asserts that exactly those become suggested, AI-generated compliance inspections in
 * lifecycle-phase order, and that a second run adds nothing (idempotent on the
 * (projectId, complianceRuleRef) pair).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ComplianceGenerationService.class, InspectionMapperImpl.class,
        TenantEntityHelper.class, EntryNumberGenerator.class, TransactionalWorkRunner.class,
        TransactionRetryTemplate.class, ComplianceGenerationServiceIT.RetryMetrics.class})
class ComplianceGenerationServiceIT extends AbstractIntegrationTest {

    /**
     * The retry template counts its restarts, and a {@code @DataJpaTest} slice carries no
     * metrics autoconfiguration, so the registry it needs is supplied here. Nothing reads the
     * counters in this test; they simply have somewhere to go.
     */
    @TestConfiguration
    static class RetryMetrics {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    private static final String RULE_PRE = "TN-BPA";        // pre-construction
    private static final String RULE_POST = "TN-OCC-CERT";  // post-construction

    @Autowired
    private ComplianceGenerationService service;

    @MockitoBean
    private OpenAiCompatibleComplianceService complianceAiService;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private ComplianceGenerationJobRepository jobRepository;

    private Long orgAId;
    private Long projectId;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        inCommittedTx(() -> {
            Organization orgA = persistOrganization("Org Compliance A");

            Project project = new Project();
            project.setProjectName("Chennai Residency");
            project.setProjectAddress("12 Mount Road, Chennai, Tamil Nadu, India");
            project.setProjectType(ProjectType.RESIDENTIAL);
            project.setStatus(ProjectCreationStatus.approved);
            project.setOrganization(orgA);
            entityManager.persist(project);

            entityManager.flush();
            orgAId = orgA.getId();
            projectId = project.getId();
        });

        // The stub applies two of the six seeded TN residential rules and rejects the rest.
        when(complianceAiService.suggestCompliances(
                any(Project.class), anyString(), any(), any(ComplianceGenerationProgress.class)))
                .thenReturn(List.of(
                        new ComplianceSuggestion(RULE_POST, true, "critical",
                                List.of("Apply for the occupancy certificate"), "Required before handover",
                                "post-construction"),
                        new ComplianceSuggestion(RULE_PRE, true, "critical",
                                List.of("Obtain the building plan approval"), "Required before work starts",
                                "pre-construction"),
                        new ComplianceSuggestion("TN-FIRE-NOC", false, null, null,
                                "Not required for this low-rise residential project", "pre-construction")
                ));

        TenantContext.setCurrentOrgId(orgAId);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        if (orgAId == null) {
            return;
        }
        // Committed seed rows survive the rollback; remove them by hand. The global
        // compliance_rules seed is left intact (it is shared reference data).
        inCommittedTx(() -> {
            entityManager.createNativeQuery(
                            "DELETE FROM compliance_generation_jobs WHERE organization_id = :org")
                    .setParameter("org", orgAId).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM inspections WHERE organization_id = :org")
                    .setParameter("org", orgAId).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM document_sequence WHERE organization_id = :org")
                    .setParameter("org", orgAId).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM project WHERE organization_id = :org")
                    .setParameter("org", orgAId).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM organization WHERE id = :org")
                    .setParameter("org", orgAId).executeUpdate();
        });
    }

    @Test
    void generatesSuggestedComplianceInspections_andIsIdempotent() {
        List<InspectionDto> created = service.generateForProject(projectId, orgAId);

        // Exactly the two applicable rules, ordered by phase: pre-construction then post.
        assertThat(created).hasSize(2);
        assertThat(created).allSatisfy(dto -> {
            assertThat(dto.type()).isEqualTo(InspectionType.COMPLIANCE);
            assertThat(dto.status()).isEqualTo(InspectionStatus.SUGGESTED);
            assertThat(dto.origin()).isEqualTo(InspectionOrigin.AI_GENERATED);
            assertThat(dto.projectId()).isEqualTo(projectId);
            assertThat(dto.inspectionNumber()).startsWith("INSP-");
            assertThat(dto.riskLevel()).isEqualTo(ComplianceRiskLevel.CRITICAL);
        });
        assertThat(created).extracting(InspectionDto::complianceRuleRef)
                .containsExactly(RULE_PRE, RULE_POST);
        assertThat(created).extracting(dto -> dto.compliancePhase().getValue())
                .containsExactly("pre-construction", "post-construction");
        assertThat(created.get(0).aiRationale()).isEqualTo("Required before work starts");

        entityManager.flush();

        // Re-run: everything already exists, so nothing new is created.
        List<InspectionDto> rerun = service.generateForProject(projectId, orgAId);
        assertThat(rerun).isEmpty();

        Long total = ((Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM inspections WHERE organization_id = :org AND type = 'COMPLIANCE'")
                .setParameter("org", orgAId)
                .getSingleResult()).longValue();
        assertThat(total).isEqualTo(2L);
    }

    /**
     * The half of the concurrency fix that only a real database can show: the unique index
     * from changelog 060 exists, applies to this table, and rejects a second inspection for
     * a rule that already has one on the project.
     *
     * <p>The insert is deliberately raw rather than a second call to the service, because
     * what is under test is the schema's guarantee and not the application's check. Before
     * the index this row was accepted, which is precisely how two overlapping runs each
     * created their own compliance inspection for the same rule.
     */
    @Test
    void theDatabaseRefusesASecondInspectionForTheSameRuleOnTheSameProject() {
        service.generateForProject(projectId, orgAId);
        entityManager.flush();

        assertThatThrownBy(() -> {
            entityManager.createNativeQuery(
                            "INSERT INTO inspections (id, inspection_number, title, type, category, "
                                    + "status, origin, project_id, compliance_rule_ref, organization_id, "
                                    + "total_check_points, passed_check_points, failed_check_points, "
                                    + "defects_found, created_at, updated_at) VALUES "
                                    + "(gen_random_uuid(), 'INSP-DUPLICATE', 'Duplicate of an existing "
                                    + "compliance', 'COMPLIANCE', 'COMPLIANCE', 'SUGGESTED', "
                                    + "'AI_GENERATED', :project, :rule, :org, 0, 0, 0, 0, "
                                    + "now()::timestamp, now()::timestamp)")
                    .setParameter("project", projectId)
                    .setParameter("rule", RULE_PRE)
                    .setParameter("org", orgAId)
                    .executeUpdate();
            entityManager.flush();
        }).satisfies(failure -> assertThat(
                SqlStateDetector.carriesSqlState(failure, SqlStateDetector.UNIQUE_VIOLATION))
                .as("the insert must be rejected by a unique violation (SQLSTATE 23505)")
                .isTrue());
    }

    /**
     * The queue's own version of the same guarantee, one step earlier in the flow.
     *
     * <p>The service does look for a job in flight before inserting, and that look is a read,
     * so two requests arriving together both see none and both insert. Nothing about the two
     * inserts conflicts in the database's eyes either, since each carries its own id, so
     * SERIALIZABLE has nothing to abort. Only the partial index stops it, and only if its
     * predicate really does hold on CockroachDB, which is what this asserts.
     *
     * <p>It matters more here than at the inspection level, because a duplicate job spends a
     * minute of inference before it gets far enough to collide.
     */
    @Test
    void theDatabaseRefusesASecondJobForAProjectThatAlreadyHasOneInFlight() {
        insertJob(ComplianceJobStatus.QUEUED);

        assertThatThrownBy(() -> insertJob(ComplianceJobStatus.RUNNING))
                .satisfies(failure -> assertThat(
                        SqlStateDetector.carriesSqlState(failure, SqlStateDetector.UNIQUE_VIOLATION))
                        .as("a second non-terminal job must be rejected by a unique violation")
                        .isTrue());
    }

    /**
     * And the other half of the predicate: finished jobs are history, and a project may have
     * as many of those as it has ever had runs. An index over all rows rather than the
     * non-terminal ones would have made the second run of a project's life impossible.
     */
    @Test
    void aProjectMayHaveManyFinishedJobsAndStillStartANewOne() {
        insertJob(ComplianceJobStatus.SUCCEEDED);
        insertJob(ComplianceJobStatus.NOTHING_TO_REPORT);
        insertJob(ComplianceJobStatus.FAILED);

        UUID fresh = insertJob(ComplianceJobStatus.QUEUED);

        assertThat(fresh).isNotNull();
    }

    /**
     * The whole of the mutual exclusion between replicas, against a real database. The second
     * claim must match no row, because the first one moved it out of QUEUED.
     */
    @Test
    void onlyOneClaimOfTwoCanWin() {
        UUID jobId = insertJob(ComplianceJobStatus.QUEUED);
        LocalDateTime now = LocalDateTime.now();

        int first = jobRepository.claim(jobId, "replica-a", now.plusMinutes(5), now);
        int second = jobRepository.claim(jobId, "replica-b", now.plusMinutes(5), now);

        assertThat(first).isEqualTo(1);
        assertThat(second)
                .as("the losing replica must be told it got nothing, not silently share the job")
                .isZero();
    }

    /**
     * A claim is named by replica and attempt together. When a lease lapses, the job is
     * requeued and the SAME replica may claim it again, so its name is back on the row and a
     * write guarded by the name alone would let the superseded run overwrite the new one.
     * The attempt moved on with the second claim, and that is what shuts the old run out.
     */
    @Test
    void aSupersededRunOnTheSameReplicaCannotWriteProgress() {
        UUID jobId = insertJob(ComplianceJobStatus.QUEUED);
        LocalDateTime past = LocalDateTime.now().minusMinutes(10);
        jobRepository.claim(jobId, "replica-a", past, past);              // attempt 1, stalls
        jobRepository.requeueExpiredLeases("worker gone", LocalDateTime.now());
        LocalDateTime now = LocalDateTime.now();
        jobRepository.claim(jobId, "replica-a", now.plusMinutes(5), now); // attempt 2, same name

        int staleWrite = jobRepository.recordProgress(
                jobId, "replica-a", 1, 2, 20, now.plusMinutes(5), now);

        assertThat(staleWrite)
                .as("the run claimed as attempt 1 no longer owns the row and must match nothing")
                .isZero();
        int currentWrite = jobRepository.recordProgress(
                jobId, "replica-a", 2, 2, 20, now.plusMinutes(5), now);
        assertThat(currentWrite).isEqualTo(1);
    }

    /** The claim counts the attempt, so a worker that dies before writing still uses one up. */
    @Test
    void theClaimItselfCountsTheAttempt() {
        UUID jobId = insertJob(ComplianceJobStatus.QUEUED);
        LocalDateTime now = LocalDateTime.now();

        jobRepository.claim(jobId, "replica-a", now.plusMinutes(5), now);

        assertThat(attemptOf(jobId)).isEqualTo(1);
    }

    /**
     * Recovery, which is the reason for the lease at all. A row left RUNNING behind a lease
     * nobody is extending has to become someone else's problem, or the user waits for a
     * worker that no longer exists.
     */
    @Test
    void aJobWhoseLeaseHasExpiredGoesBackToTheQueueWhileAttemptsRemain() {
        UUID jobId = insertJob(ComplianceJobStatus.QUEUED);
        LocalDateTime past = LocalDateTime.now().minusMinutes(10);
        jobRepository.claim(jobId, "replica-a", past, past);

        int requeued = jobRepository.requeueExpiredLeases("worker gone", LocalDateTime.now());

        assertThat(requeued).isEqualTo(1);
        assertThat(statusOf(jobId)).isEqualTo("QUEUED");
    }

    /**
     * And when there are none left it is failed outright rather than left RUNNING for ever
     * behind a lease nothing will extend.
     */
    @Test
    void aJobWhoseLeaseHasExpiredWithNoAttemptsLeftIsFailed() {
        UUID jobId = insertJob(ComplianceJobStatus.QUEUED);
        LocalDateTime past = LocalDateTime.now().minusMinutes(10);
        jobRepository.claim(jobId, "replica-a", past, past);
        jobRepository.requeueExpiredLeases("worker gone", LocalDateTime.now());
        jobRepository.claim(jobId, "replica-a", past, past);
        jobRepository.requeueExpiredLeases("worker gone", LocalDateTime.now());
        jobRepository.claim(jobId, "replica-a", past, past);

        int failed = jobRepository.failExpiredLeasesOutOfAttempts("gave up", LocalDateTime.now());

        assertThat(failed).isEqualTo(1);
        assertThat(statusOf(jobId)).isEqualTo("FAILED");
    }

    /** Inserts a job row directly, so what is under test is the schema and not the service. */
    private UUID insertJob(ComplianceJobStatus status) {
        UUID id = UUID.randomUUID();
        entityManager.createNativeQuery(
                        "INSERT INTO compliance_generation_jobs (id, organization_id, project_id, "
                                + "status, rules_total, rules_assessed, batches_total, batches_done, "
                                + "created_count, attempt, max_attempts, created_at, updated_at) VALUES "
                                + "(:id, :org, :project, :status, 6, 0, 1, 0, 0, 0, 3, "
                                + "now()::timestamp, now()::timestamp)")
                .setParameter("id", id)
                .setParameter("org", orgAId)
                .setParameter("project", projectId)
                .setParameter("status", status.name())
                .executeUpdate();
        entityManager.flush();
        return id;
    }

    private String statusOf(UUID jobId) {
        return (String) entityManager.createNativeQuery(
                        "SELECT status FROM compliance_generation_jobs WHERE id = :id")
                .setParameter("id", jobId).getSingleResult();
    }

    private int attemptOf(UUID jobId) {
        return ((Number) entityManager.createNativeQuery(
                        "SELECT attempt FROM compliance_generation_jobs WHERE id = :id")
                .setParameter("id", jobId).getSingleResult()).intValue();
    }

    private void inCommittedTx(Runnable work) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.executeWithoutResult(status -> work.run());
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        entityManager.persist(org);
        return org;
    }
}
