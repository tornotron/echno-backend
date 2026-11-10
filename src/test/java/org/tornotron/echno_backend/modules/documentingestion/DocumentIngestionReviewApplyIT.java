package org.tornotron.echno_backend.modules.documentingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;
import org.tornotron.echno_backend.modules.access.guard.AccessGuard;
import org.tornotron.echno_backend.modules.documentingestion.apply.ApplyGuard;
import org.tornotron.echno_backend.modules.documentingestion.apply.ApplyService;
import org.tornotron.echno_backend.modules.documentingestion.apply.IngestionApplier;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateFlag;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractionMethod;
import org.tornotron.echno_backend.modules.documentingestion.dto.ApplyResultDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.CandidateDecision;
import org.tornotron.echno_backend.modules.documentingestion.dto.CandidateDecision.Action;
import org.tornotron.echno_backend.modules.documentingestion.dto.DecisionOutcomeDto.Outcome;
import org.tornotron.echno_backend.modules.documentingestion.dto.ProvenanceDto;
import org.tornotron.echno_backend.modules.documentingestion.extraction.CandidateWriter;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ProposedCandidate;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ProposedCandidate.ProposedSource;
import org.tornotron.echno_backend.modules.documentingestion.repository.CandidateRepository;
import org.tornotron.echno_backend.modules.documentingestion.review.ProvenanceService;
import org.tornotron.echno_backend.modules.documentingestion.review.ReviewAccess;
import org.tornotron.echno_backend.modules.documentingestion.review.ReviewService;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Review and apply on the real migration, with every transaction committed as it would be in a
 * request, so a failed batch really rolls back. A test applier writes projects: the live tables named
 * in the design stay untouched until the apply call, a candidate with no source is never accepted, a
 * failed batch resumes with no row written twice, and the target's guard is asked for the caller.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ReviewService.class, ApplyService.class, ApplyGuard.class, CandidateWriter.class, ProvenanceService.class,
        TransactionalWorkRunner.class, TransactionRetryTemplate.class, SimpleMeterRegistry.class,
        UserContextService.class, TenantEntityHelper.class, DocumentIngestionReviewApplyIT.ProjectApplier.class})
class DocumentIngestionReviewApplyIT extends AbstractIntegrationTest {

    static final String GUARD = "@access.can('projects', 'edit')";
    static final List<String> LIVE_TABLES =
            List.of("project", "wbs_element", "contract_boq_item", "contract_milestone", "project_risk");

    /** Writes a project per candidate. Fails on {@code fail: true}, and once on a name it has not seen. */
    @TestConfiguration
    static class ProjectApplier implements IngestionApplier {

        static final Set<String> FLAKY_ONCE = ConcurrentHashMap.newKeySet();

        @Autowired
        ProjectRepository projects;

        @Override
        public CandidateKind kind() {
            return CandidateKind.PROJECT_FIELD;
        }

        @Override
        public String targetGuard() {
            return GUARD;
        }

        @Override
        public AppliedTarget apply(Candidate candidate, Map<String, Object> accepted) {
            if (Boolean.TRUE.equals(accepted.get("fail"))) {
                throw new IllegalArgumentException("The target refused the value");
            }
            String name = (String) accepted.get("name");
            if (name.startsWith("flaky") && FLAKY_ONCE.add(name)) {
                throw new IllegalStateException("A transient failure");
            }
            Project project = new Project();
            project.setProjectName(name);
            project.setOrganization(candidate.getOrganization());
            project = projects.save(project);
            return new AppliedTarget("PROJECT", String.valueOf(project.getId()), Map.of("name", "projectName"));
        }
    }

    @Autowired
    CandidateWriter writer;

    @Autowired
    ReviewService review;

    @Autowired
    ApplyService apply;

    @Autowired
    ProvenanceService provenance;

    @Autowired
    CandidateRepository candidates;

    @Autowired
    OrganizationRepository organizations;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean(name = "access")
    AccessGuard access;

    @MockitoBean
    ReviewAccess reviewAccess;

    Long orgId;
    UUID packageId;
    UUID documentId;

    @BeforeEach
    void aPackageWithADocument() {
        Organization org = new Organization();
        org.setOrganizationName("Review " + UUID.randomUUID());
        org.setOrganizationAddress("address");
        org.setOrganizationEmail(UUID.randomUUID() + "@example.test");
        org.setOrganizationPhone("0000000000");
        orgId = organizations.save(org).getId();
        packageId = UUID.randomUUID();
        documentId = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_ingest_package (id, organization_id, name, stage, status, created_at, updated_at) "
                + "VALUES (?, ?, 'Tender', 'TENDER', 'OPEN', now(), now())", packageId, orgId);
        jdbc.update("INSERT INTO doc_ingest_document (id, organization_id, package_id, source_key, file_name, file_type, "
                        + "size_bytes, status, created_at, updated_at) VALUES (?, ?, ?, 'k', 'NIT.pdf', 'PDF', 1, 'PARSED', now(), now())",
                documentId, orgId, packageId);
        TenantContext.setCurrentOrgId(orgId);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("pm", "n/a", "ROLE_USER"));
        when(access.can(anyString(), anyString())).thenReturn(true);
        when(reviewAccess.mayReview(any())).thenReturn(true);
    }

    @AfterEach
    void removeRows() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        jdbc.update("DELETE FROM doc_ingest_provenance WHERE organization_id = ?", orgId);
        jdbc.update("DELETE FROM doc_ingest_source_ref WHERE organization_id = ?", orgId);
        jdbc.update("UPDATE doc_ingest_candidate SET supersedes_candidate_id = NULL WHERE organization_id = ?", orgId);
        jdbc.update("DELETE FROM doc_ingest_candidate WHERE organization_id = ?", orgId);
        jdbc.update("DELETE FROM doc_ingest_document WHERE organization_id = ?", orgId);
        jdbc.update("DELETE FROM doc_ingest_package WHERE organization_id = ?", orgId);
        jdbc.update("DELETE FROM project WHERE organization_id = ?", orgId);
        jdbc.update("DELETE FROM organization WHERE id = ?", orgId);
    }

    @Test
    void noLiveTableChangesUntilTheApplyCall() {
        List<UUID> ids = List.of(propose("Tower A"), propose("Tower B"), propose("Tower C"));
        Map<String, Long> before = liveCounts();

        assertThat(review.decide(packageId, accept(ids))).allSatisfy(o -> assertThat(o.outcome()).isEqualTo(Outcome.ACCEPTED));
        assertThat(liveCounts()).isEqualTo(before);

        ApplyResultDto result = apply.apply(packageId, CandidateKind.PROJECT_FIELD);

        assertThat(result).isEqualTo(new ApplyResultDto(3, 0, 0));
        assertThat(liveCounts().get("project")).isEqualTo(before.get("project") + 3);
        Candidate applied = candidates.findById(ids.get(0)).orElseThrow();
        assertThat(applied.getStatus()).isEqualTo(CandidateStatus.APPLIED);
        List<ProvenanceDto> trail = provenance.forTarget("PROJECT", applied.getAppliedTargetId());
        assertThat(trail).singleElement().satisfies(p -> {
            assertThat(p.targetField()).isEqualTo("projectName");
            assertThat(p.valueAccepted()).isEqualTo("Tower A");
            assertThat(p.enteredByHand()).isFalse();
            assertThat(p.source().page()).isEqualTo(2);
            assertThat(p.source().quote()).isEqualTo("Name of work: Tower A");
        });
    }

    @Test
    void aKindTheCallerMayNotReviewIsRefusedAndLeftAsItWas() {
        UUID id = propose("Tower Q");
        when(reviewAccess.mayReview(CandidateKind.PROJECT_FIELD)).thenReturn(false);

        assertThat(review.decide(packageId, accept(List.of(id)))).singleElement()
                .satisfies(o -> assertThat(o.outcome()).isEqualTo(Outcome.REFUSED_ROLE));
        assertThat(candidates.findById(id).orElseThrow().getStatus()).isEqualTo(CandidateStatus.PROPOSED);
    }

    @Test
    void aCandidateWithNoSourceIsNeverAccepted() {
        UUID id = writer.propose(new ProposedCandidate(packageId, documentId, null, CandidateKind.PROJECT_FIELD,
                "name", Map.of("name", "Invented"), List.of(), ExtractionMethod.LLM, List.of(), null));

        assertThat(review.decide(packageId, accept(List.of(id)))).singleElement()
                .satisfies(o -> assertThat(o.outcome()).isEqualTo(Outcome.REFUSED_NO_SOURCE));
        assertThat(candidates.findById(id).orElseThrow().getStatus()).isEqualTo(CandidateStatus.PROPOSED);
    }

    @Test
    void aFlaggedCandidateIsSkippedUntilEachFlagIsAcknowledged() {
        UUID id = writer.propose(new ProposedCandidate(packageId, documentId, null, CandidateKind.PROJECT_FIELD, "name",
                Map.of("name", "Tower F"), List.of(new CandidateFlag("QUOTE_NOT_FOUND", "The quote is not on page 2")),
                ExtractionMethod.LLM, List.of(source("name", "Tower F")), null));

        assertThat(review.decide(packageId, accept(List.of(id)))).singleElement()
                .satisfies(o -> assertThat(o.outcome()).isEqualTo(Outcome.SKIPPED_FLAGGED));
        assertThat(review.decide(packageId, List.of(new CandidateDecision(id, Action.ACCEPT, null, List.of("QUOTE_NOT_FOUND")))))
                .singleElement().satisfies(o -> assertThat(o.outcome()).isEqualTo(Outcome.ACCEPTED));
    }

    @Test
    void aCorrectionKeepsTheExtractedValueBesideTheAcceptedOne() {
        UUID id = propose("Towr A");
        review.decide(packageId, List.of(new CandidateDecision(id, Action.EDIT, Map.of("name", "Tower A"), null)));

        apply.apply(packageId, CandidateKind.PROJECT_FIELD);

        Candidate c = candidates.findById(id).orElseThrow();
        assertThat(c.getPayload()).containsEntry("name", "Towr A");
        assertThat(provenance.forTarget("PROJECT", c.getAppliedTargetId())).singleElement().satisfies(p -> {
            assertThat(p.valueExtracted()).isEqualTo("Towr A");
            assertThat(p.valueAccepted()).isEqualTo("Tower A");
        });
    }

    @Test
    void aFailedBatchResumesWithNoRowWrittenTwice() {
        long projectsBefore = liveCounts().get("project");
        UUID first = propose("Tower 1");
        UUID refused = proposeFailing("Tower 2");
        UUID third = propose("Tower 3");
        review.decide(packageId, accept(List.of(first, refused, third)));

        assertThat(apply.apply(packageId, CandidateKind.PROJECT_FIELD)).isEqualTo(new ApplyResultDto(2, 1, 0));
        Candidate failed = candidates.findById(refused).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(CandidateStatus.APPLY_FAILED);
        assertThat(failed.getApplyError()).contains("refused");
        assertThat(apply.apply(packageId, CandidateKind.PROJECT_FIELD)).isEqualTo(new ApplyResultDto(0, 0, 0));

        review.decide(packageId, List.of(new CandidateDecision(refused, Action.EDIT, Map.of("fail", false), null)));
        assertThat(apply.apply(packageId, CandidateKind.PROJECT_FIELD)).isEqualTo(new ApplyResultDto(1, 0, 0));
        assertThat(liveCounts().get("project")).isEqualTo(projectsBefore + 3);
    }

    @Test
    void aTransientFailureInABatchLandsEveryRowExactlyOnce() {
        long projectsBefore = liveCounts().get("project");
        String flaky = "flaky " + UUID.randomUUID();
        review.decide(packageId, accept(List.of(propose("Tower X"), propose(flaky), propose("Tower Y"))));

        assertThat(apply.apply(packageId, CandidateKind.PROJECT_FIELD)).isEqualTo(new ApplyResultDto(3, 0, 0));
        assertThat(liveCounts().get("project")).isEqualTo(projectsBefore + 3);
    }

    @Test
    void theTargetsGuardIsAskedForTheCallerAndAJobHasNoCaller() {
        UUID id = propose("Tower G");
        review.decide(packageId, accept(List.of(id)));

        when(access.can("projects", "edit")).thenReturn(false);
        assertThatThrownBy(() -> apply.apply(packageId, CandidateKind.PROJECT_FIELD)).isInstanceOf(AccessDeniedException.class);

        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> apply.apply(packageId, CandidateKind.PROJECT_FIELD))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThat(candidates.findById(id).orElseThrow().getStatus()).isEqualTo(CandidateStatus.ACCEPTED);
    }

    @Test
    void acceptingARevisionSupersedesTheValueItReplaces() {
        UUID original = propose("Tower R");
        UUID revision = writer.propose(new ProposedCandidate(packageId, documentId, null, CandidateKind.PROJECT_FIELD,
                "name", Map.of("name", "Tower R2"), List.of(), ExtractionMethod.DETERMINISTIC,
                List.of(source("name", "Tower R2")), original));

        review.decide(packageId, accept(List.of(revision)));

        assertThat(candidates.findById(original).orElseThrow().getStatus()).isEqualTo(CandidateStatus.SUPERSEDED);
        assertThat(review.decide(packageId, accept(List.of(original)))).singleElement()
                .satisfies(o -> assertThat(o.outcome()).isEqualTo(Outcome.REFUSED_STATE));
    }

    @Test
    void aKindWithNoApplierIsRefusedPlainly() {
        assertThatThrownBy(() -> apply.apply(packageId, CandidateKind.BOQ_ITEM))
                .hasMessageContaining("cannot be applied yet");
    }

    private UUID propose(String name) {
        return writer.propose(new ProposedCandidate(packageId, documentId, null, CandidateKind.PROJECT_FIELD, "name",
                new LinkedHashMap<>(Map.of("name", name)), List.of(), ExtractionMethod.DETERMINISTIC,
                List.of(source("name", name)), null));
    }

    private UUID proposeFailing(String name) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("fail", true);
        return writer.propose(new ProposedCandidate(packageId, documentId, null, CandidateKind.PROJECT_FIELD, "name",
                payload, List.of(), ExtractionMethod.DETERMINISTIC, List.of(source("name", name)), null));
    }

    private ProposedSource source(String field, String value) {
        return new ProposedSource(field, documentId, "e".repeat(64), 2, List.of(0.1, 0.2, 0.5, 0.25), null, null, null,
                null, "Name of work: " + value, true);
    }

    private static List<CandidateDecision> accept(List<UUID> ids) {
        List<CandidateDecision> decisions = new ArrayList<>();
        ids.forEach(id -> decisions.add(new CandidateDecision(id, Action.ACCEPT, null, null)));
        return decisions;
    }

    private Map<String, Long> liveCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        LIVE_TABLES.forEach(table -> counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)));
        return counts;
    }
}
