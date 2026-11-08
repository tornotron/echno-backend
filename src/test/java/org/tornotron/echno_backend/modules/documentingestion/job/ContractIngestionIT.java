package org.tornotron.echno_backend.modules.documentingestion.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.llm.ChatCompletionClient;
import org.tornotron.echno_backend.common.llm.ChatCompletionOutcome;
import org.tornotron.echno_backend.common.llm.ChatCompletionRequest;
import org.tornotron.echno_backend.common.llm.ChatCompletionResult;
import org.tornotron.echno_backend.common.llm.LlmEndpointProperties;
import org.tornotron.echno_backend.common.multitenancy.HibernateFilterConfig;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.multitenancy.TenantIsolationListenerRegistrar;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.common.multitenancy.UnscopedAccessGuard;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionStorageLayout;
import org.tornotron.echno_backend.modules.documentingestion.apply.ApplyGuard;
import org.tornotron.echno_backend.modules.documentingestion.apply.ApplyService;
import org.tornotron.echno_backend.modules.documentingestion.apply.ContractTermApplier;
import org.tornotron.echno_backend.modules.documentingestion.apply.ObligationApplier;
import org.tornotron.echno_backend.modules.documentingestion.apply.RiskRaiser;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus;
import org.tornotron.echno_backend.modules.documentingestion.dto.ApplyResultDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.CandidateDecision;
import org.tornotron.echno_backend.modules.documentingestion.dto.CandidateDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.ContractTermCoverageDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.ContractTermEntryDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.DecisionOutcomeDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.ObligationEntryDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.ProvenanceDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.RaiseRiskRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.RaisedRiskDto;
import org.tornotron.echno_backend.modules.documentingestion.extraction.CandidateWriter;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ClassificationWriter;
import org.tornotron.echno_backend.modules.documentingestion.extraction.classify.ClassificationExtractor;
import org.tornotron.echno_backend.modules.documentingestion.extraction.contract.ContractTermLinker;
import org.tornotron.echno_backend.modules.documentingestion.extraction.contract.ContractTermsExtractor;
import org.tornotron.echno_backend.modules.documentingestion.extraction.contract.TermOverrides;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkReader;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkRecorder;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractorRegistry;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.IngestLlmProperties;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.LlmBudget;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.LlmGate;
import org.tornotron.echno_backend.modules.documentingestion.parsing.ParsedOutputReader;
import org.tornotron.echno_backend.modules.documentingestion.review.ProvenanceService;
import org.tornotron.echno_backend.modules.documentingestion.review.ReviewService;
import org.tornotron.echno_backend.modules.documentingestion.service.ClassificationService;
import org.tornotron.echno_backend.modules.documentingestion.service.ContractRegisterService;
import org.tornotron.echno_backend.modules.documentingestion.service.ExtractionService;
import org.tornotron.echno_backend.modules.documentingestion.service.IngestSettingsService;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.risk.ProjectRiskService;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Step 17 of the build plan, on the real migration with the LLM endpoint stubbed and the worker's outputs
 * served from a mocked object store: the done-when lines of design section 4.5. A general conditions PDF and
 * a special conditions PDF are classified, confirmed and read; each of the 19 terms is found or reads "not
 * found, pages searched"; the special conditions' LD rate is shown beside the general one and proposed;
 * accepting it and raising a risk twice gives one risk. Obligations with a deadline land in the register.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@TestPropertySource(properties = {
        "echno.modules.document-ingestion.enabled=true",
        "echno.modules.document-ingestion.allowed-org-ids=" + ContractIngestionIT.ORG,
        "echno.modules.document-ingestion.llm.initial-delay-millis=86400000"
})
@Import({ExtractJobDispatcher.class, ExtractJobWorker.class, ExtractJobQueue.class, ChunkReader.class,
        ChunkRecorder.class, LlmGate.class, LlmBudget.class, IngestLlmProperties.class, LlmEndpointProperties.class,
        ExtractorRegistry.class, ClassificationExtractor.class, ClassificationWriter.class, CandidateWriter.class,
        ContractTermsExtractor.class, ContractTermLinker.class, ParsedOutputReader.class, IngestSettingsService.class,
        ClassificationService.class, ExtractionService.class, ReviewService.class, ApplyService.class, ApplyGuard.class,
        ContractTermApplier.class, ObligationApplier.class, RiskRaiser.class, ContractRegisterService.class,
        ProvenanceService.class, ProjectRiskService.class,
        TenantScopedJobRunner.class, TransactionalWorkRunner.class, TransactionRetryTemplate.class,
        SimpleMeterRegistry.class, UserContextService.class, TenantEntityHelper.class,
        AopAutoConfiguration.class, HibernateFilterConfig.class, TenantIsolationListenerRegistrar.class,
        UnscopedAccessGuard.class})
class ContractIngestionIT extends AbstractIntegrationTest {

    static final long ORG = 913_017_101L;
    static final Executor DIRECT = Runnable::run;

    @Autowired
    ExtractJobDispatcher dispatcher;

    @Autowired
    ClassificationService classification;

    @Autowired
    ReviewService review;

    @Autowired
    ApplyService apply;

    @Autowired
    ProvenanceService provenance;

    @Autowired
    ContractRegisterService registers;

    @Autowired
    RiskRaiser riskRaiser;

    @Autowired
    IngestLlmProperties properties;

    @Autowired
    OrganizationRepository organizations;

    @Autowired
    ProjectRepository projects;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    ChatCompletionClient client;

    @MockitoBean
    FileStorageService storage;

    @MockitoBean(name = "orgSecurity")
    OrganizationSecurityService orgSecurity;

    final Map<String, byte[]> objects = new HashMap<>();
    final List<ChatCompletionRequest> sent = new ArrayList<>();
    UUID packageId;

    @BeforeEach
    void anOrganizationAndAnEmptyPackage() {
        jdbc.update("INSERT INTO organization (id, organization_name, organization_address, organization_email, "
                + "organization_phone) VALUES (?, ?, 'address', ?, '0000000000')", ORG, "Contract " + UUID.randomUUID(),
                UUID.randomUUID() + "@example.test");
        packageId = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_ingest_package (id, organization_id, name, stage, status, created_at, updated_at) "
                + "VALUES (?, ?, 'Riverside depot tender', 'TENDER', 'OPEN', now(), now())", packageId, ORG);
        properties.setBackoff(List.of(Duration.ZERO, Duration.ZERO, Duration.ZERO));
        when(client.isAvailable()).thenReturn(true);
        when(client.complete(any())).thenAnswer(inv -> {
            ChatCompletionRequest req = inv.getArgument(0);
            if (!req.systemPrompt().equals(ContractTermsExtractor.PROMPT.system())) {
                return ok("{\"pages\": []}");
            }
            sent.add(req);
            return ok(req.userPrompt().contains("SPECIAL CONDITIONS") ? SCC_ANSWER : GCC_ANSWER);
        });
        when(storage.readObject(any(), anyLong())).thenAnswer(inv -> Optional.ofNullable(objects.get(inv.<String>getArgument(0)))
                .map(b -> new FileStorageService.StoredObject(inv.getArgument(0), "application/json", b)));
        when(storage.objectExists(argThat(k -> k != null && k.endsWith(DocumentIngestionStorageLayout.META_FILE))))
                .thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("pm", "n/a", "ROLE_USER"));
        TenantContext.setCurrentOrgId(ORG);
    }

    @AfterEach
    void removeRows() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        for (String table : List.of("doc_ingest_provenance", "doc_ingest_llm_call", "doc_ingest_source_ref",
                "doc_ingest_contract_term", "doc_ingest_obligation", "doc_ingest_candidate", "doc_ingest_extract_job",
                "doc_ingest_segment", "doc_ingest_document", "doc_ingest_package", "project_risk", "project")) {
            jdbc.update("DELETE FROM " + table + " WHERE organization_id = ?", ORG);
        }
        jdbc.update("DELETE FROM organization WHERE id = ?", ORG);
    }

    static final String GCC_ANSWER = """
            {"terms": [
              {"term": "ld_rate", "value": "0.5% of the Contract Price per week of delay", "clause": "47.1", "page": 2, "quote": "47.1 Liquidated damages shall be 0.5% of the Contract Price per week of delay."},
              {"term": "ld_cap", "value": "10% of the Contract Price", "clause": "Clause 47.2", "page": 2, "quote": "47.2 The total liquidated damages shall not exceed 10% of the Contract Price."},
              {"term": "dlp", "value": "12 months from completion", "clause": "35.1", "page": 3, "quote": "35.1 The defects liability period is 12 months from completion."},
              {"term": "duration", "value": "24 months", "clause": "35.1", "page": 3, "quote": "35.1 The defects liability period is 12 months from completion."}
            ],
            "obligations": [
              {"clause": "10", "responsible_party": "Contractor", "trigger": "the notice to commence", "deadline": "15 days", "required_document": "programme", "topic": "programme", "page": 1, "quote": "The Contractor shall submit a programme within 15 days of the notice to commence.", "clause_quote": "10. Programme"}
            ]}""";

    static final String SCC_ANSWER = """
            {"terms": [
              {"term": "ld_rate", "value": "1% of the Contract Price per week of delay", "clause": "12", "page": 1, "quote": "12. Liquidated damages: 1% of the Contract Price per week of delay."}
            ],
            "obligations": []}""";

    @Test
    void eachTermIsFoundOrNotFoundWithThePagesSearchedAndTheSpecialConditionsOverrideShowsBothClauses() {
        UUID gcc = gccPdf();
        UUID scc = sccPdf();
        classifyAndRead(gcc, scc);

        assertThat(sent).as("one call per document").hasSize(2);
        Map<String, List<CandidateDto>> terms = termsByKey();
        assertThat(terms).containsOnlyKeys("ld_rate", "ld_cap", "dlp");
        assertThat(terms).as("a number not in its quote is dropped").doesNotContainKey("duration");

        CandidateDto general = only(terms.get("ld_rate"), gcc);
        CandidateDto special = only(terms.get("ld_rate"), scc);
        assertThat(general.flags()).extracting(f -> f.code()).containsExactly(TermOverrides.OVERRIDDEN_BY_SCC);
        assertThat(general.flags().get(0).message()).contains("clause 12");
        assertThat(special.flags()).isEmpty();
        assertThat(special.supersedesCandidateId()).isEqualTo(general.id());
        assertThat(special.payload()).containsEntry("value", "1% of the Contract Price per week of delay")
                .containsEntry("clause", "12");
        assertThat(only(terms.get("ld_cap"), gcc).payload()).containsEntry("clause", "47.2");

        ContractTermCoverageDto coverage = registers.coverage(packageId);
        assertThat(coverage.notice()).contains("not a complete list").doesNotContain("all obligations");
        assertThat(coverage.terms()).hasSize(19);
        assertThat(coverage.read()).hasSize(2).allSatisfy(p -> assertThat(p.status()).isEqualTo("DONE"));
        ContractTermCoverageDto.TermCoverage ld = term(coverage, "ld_rate");
        assertThat(ld.status()).isEqualTo(ContractTermCoverageDto.Status.FOUND);
        assertThat(ld.proposedCandidateId()).isEqualTo(special.id());
        assertThat(ld.found()).extracting(ContractTermCoverageDto.FoundValue::clause).containsExactlyInAnyOrder("47.1", "12");
        assertThat(ld.found()).extracting(ContractTermCoverageDto.FoundValue::docClass).containsExactlyInAnyOrder("GCC", "SCC");
        assertThat(ld.found()).filteredOn(ContractTermCoverageDto.FoundValue::overridden).singleElement()
                .satisfies(v -> assertThat(v.candidateId()).isEqualTo(general.id()));
        List<ContractTermCoverageDto.TermCoverage> notFound = coverage.terms().stream()
                .filter(t -> t.status() == ContractTermCoverageDto.Status.NOT_FOUND).toList();
        assertThat(notFound).hasSize(16).allSatisfy(t -> assertThat(t.message())
                .startsWith("Not found, pages searched: ").contains("1 to 3 of Conditions.pdf")
                .contains("1 of Special conditions.pdf"));
        assertThat(term(coverage, "retention").status()).isEqualTo(ContractTermCoverageDto.Status.NOT_FOUND);
    }

    @Test
    void acceptingTheLdTermAndRaisingARiskTwiceCreatesOneRisk() {
        Long projectId = project("Riverside depot");
        jdbc.update("UPDATE doc_ingest_package SET project_id = ? WHERE id = ?", projectId, packageId);
        UUID gcc = gccPdf();
        UUID scc = sccPdf();
        classifyAndRead(gcc, scc);
        Map<String, List<CandidateDto>> terms = termsByKey();
        CandidateDto general = only(terms.get("ld_rate"), gcc);
        CandidateDto special = only(terms.get("ld_rate"), scc);

        List<DecisionOutcomeDto> outcomes = review.decide(packageId, terms.values().stream().flatMap(List::stream)
                .map(c -> new CandidateDecision(c.id(), CandidateDecision.Action.ACCEPT, null, null)).toList());
        assertThat(outcomes).filteredOn(o -> o.candidateId().equals(general.id())).singleElement()
                .as("flagged, so passed by in bulk; or already superseded when it came after the special one")
                .satisfies(o -> assertThat(o.outcome()).isIn(DecisionOutcomeDto.Outcome.SKIPPED_FLAGGED,
                        DecisionOutcomeDto.Outcome.REFUSED_STATE));
        assertThat(status(general.id())).as("accepting the special conditions' value supersedes the general one")
                .isEqualTo("SUPERSEDED");
        long risksBefore = riskCount();

        ApplyResultDto result = apply.apply(packageId, CandidateKind.CONTRACT_TERM);

        assertThat(result.applied()).isEqualTo(3);
        assertThat(result.failed()).isZero();
        assertThat(riskCount()).as("applying a term writes no risk").isEqualTo(risksBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract_deduction_rule WHERE organization_id = ?",
                Long.class, ORG)).as("tender terms are not sub-contract deduction rules").isZero();
        ContractTermEntryDto ld = registers.terms(packageId).stream().filter(t -> t.term().equals("ld_rate"))
                .findFirst().orElseThrow();
        assertThat(ld.value()).isEqualTo("1% of the Contract Price per week of delay");
        assertThat(ld.clause()).isEqualTo("12");
        assertThat(ld.sourceClass()).isEqualTo("SCC");
        assertThat(ld.candidateId()).isEqualTo(special.id());
        assertThat(ld.projectId()).isEqualTo(projectId);
        List<ProvenanceDto> onTerm = provenance.forTarget(ContractTermApplier.TARGET, ld.id().toString());
        assertThat(onTerm).extracting(ProvenanceDto::targetField).containsExactlyInAnyOrder("value", "clause");
        assertThat(onTerm).allSatisfy(p -> assertThat(p.source()).isNotNull());

        RaisedRiskDto first = riskRaiser.raiseFromTerm(ld.id(), null);
        RaisedRiskDto second = riskRaiser.raiseFromTerm(ld.id(),
                new RaiseRiskRequest("Another title", null, "high", "major", null, null));

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.riskId()).isEqualTo(first.riskId());
        assertThat(riskCount()).isEqualTo(risksBefore + 1);
        Map<String, Object> risk = jdbc.queryForMap("SELECT title, category, import_ref, project_id FROM project_risk "
                + "WHERE organization_id = ?", ORG);
        assertThat(risk).containsEntry("import_ref", RiskRaiser.IMPORT_REF_PREFIX + special.id())
                .containsEntry("category", "contractual-legal").containsEntry("project_id", projectId);
        assertThat((String) risk.get("title")).isEqualTo("Contract term: LD rate (clause 12)");
        assertThat(registers.terms(packageId)).filteredOn(t -> t.term().equals("ld_rate")).singleElement()
                .satisfies(t -> assertThat(t.riskId()).isEqualTo(first.riskId()));
        assertThat(provenance.forTarget(RiskRaiser.TARGET_RISK, first.riskId().toString())).singleElement()
                .satisfies(p -> {
                    assertThat(p.targetField()).isEqualTo("description");
                    assertThat(p.source()).isNotNull();
                });
    }

    @Test
    void obligationsWithADeadlineLandInTheRegisterAndCanRaiseARisk() {
        Long projectId = project("Riverside depot");
        jdbc.update("UPDATE doc_ingest_package SET project_id = ? WHERE id = ?", projectId, packageId);
        UUID gcc = gccPdf();
        classifyAndRead(gcc);
        List<CandidateDto> obligations = review.list(packageId, CandidateKind.OBLIGATION, CandidateStatus.PROPOSED, null,
                0, 50).getContent();
        assertThat(obligations).singleElement().satisfies(o -> {
            assertThat(o.naturalKey()).isEqualTo("10|15 days");
            assertThat(o.payload()).containsEntry("clause", "10").containsEntry("deadline", "15 days")
                    .containsEntry("responsible_party", "Contractor").containsEntry("topic", "programme");
            assertThat(o.flags()).isEmpty();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM doc_ingest_obligation WHERE organization_id = ?",
                Long.class, ORG)).as("nothing in the register before apply").isZero();

        review.decide(packageId, List.of(new CandidateDecision(obligations.get(0).id(), CandidateDecision.Action.ACCEPT,
                null, null)));
        assertThat(apply.apply(packageId, CandidateKind.OBLIGATION).applied()).isEqualTo(1);

        ObligationEntryDto row = registers.obligations(packageId, 0, 50).getContent().get(0);
        assertThat(row.clause()).isEqualTo("10");
        assertThat(row.deadline()).isEqualTo("15 days");
        assertThat(row.deadlineDays()).isEqualTo(15);
        assertThat(row.responsibleParty()).isEqualTo("Contractor");
        assertThat(row.trigger()).isEqualTo("the notice to commence");
        assertThat(row.requiredDocument()).isEqualTo("programme");
        assertThat(row.sourceClass()).isEqualTo("GCC");
        assertThat(provenance.forTarget(ObligationApplier.TARGET, row.id().toString()))
                .extracting(ProvenanceDto::targetField)
                .contains("clause", "deadline", "responsibleParty", "topic", "trigger", "requiredDocument");

        RaisedRiskDto raised = riskRaiser.raiseFromObligation(row.id(), RaiseRiskRequest.defaults());
        assertThat(raised.created()).isTrue();
        assertThat(riskRaiser.raiseFromObligation(row.id(), null).riskId()).isEqualTo(raised.riskId());
        assertThat(riskCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT title FROM project_risk WHERE organization_id = ?", String.class, ORG))
                .isEqualTo("Obligation: programme within 15 days (clause 10)");
    }

    @Test
    void aRiskNeedsAProjectAndTheRiskManagersRole() {
        UUID gcc = gccPdf();
        classifyAndRead(gcc);
        CandidateDto ldCap = only(termsByKey().get("ld_cap"), gcc);
        review.decide(packageId, List.of(new CandidateDecision(ldCap.id(), CandidateDecision.Action.ACCEPT, null, null)));
        apply.apply(packageId, CandidateKind.CONTRACT_TERM);
        UUID termId = registers.terms(packageId).get(0).id();

        assertThatThrownBy(() -> riskRaiser.raiseFromTerm(termId, null))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("no project");

        jdbc.update("UPDATE doc_ingest_package SET project_id = ? WHERE id = ?", project("Riverside depot"), packageId);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant("system-admin", "project-manager")).thenReturn(false);
        assertThatThrownBy(() -> riskRaiser.raiseFromTerm(termId, null)).isInstanceOf(AccessDeniedException.class);
        assertThat(riskCount()).isZero();
    }

    // --------------------------------------------------------------------------------------------
    // Fixtures
    // --------------------------------------------------------------------------------------------

    UUID gccPdf() {
        return pdf("Conditions.pdf",
                page(1, "GENERAL CONDITIONS OF CONTRACT", "10. Programme",
                        "The Contractor shall submit a programme within 15 days of the notice to commence."),
                page(2, "47.1 Liquidated damages shall be 0.5% of the Contract Price per week of delay.",
                        "47.2 The total liquidated damages shall not exceed 10% of the Contract Price."),
                page(3, "35.1 The defects liability period is 12 months from completion."));
    }

    UUID sccPdf() {
        return pdf("Special conditions.pdf",
                page(1, "SPECIAL CONDITIONS OF CONTRACT",
                        "12. Liquidated damages: 1% of the Contract Price per week of delay."));
    }

    void classifyAndRead(UUID... docs) {
        for (UUID doc : docs) {
            jdbc.update("INSERT INTO doc_ingest_extract_job (id, organization_id, package_id, document_id, extractor_key, "
                    + "extractor_version, status, queued_at, created_at, updated_at) VALUES (gen_random_uuid(), ?, ?, ?, "
                    + "'classify', '1', 'QUEUED', now(), now(), now())", ORG, packageId, doc);
        }
        runAll();
        Map<UUID, String> classes = new HashMap<>();
        classification.list(packageId, 0, 50).getContent().forEach(d -> classes.put(d.documentId(), d.docClass()));
        assertThat(classes.values()).as("classified by their headings").allMatch(c -> c.equals("GCC") || c.equals("SCC"));
        classification.confirmAll(packageId);
        runAll();
    }

    void runAll() {
        for (int i = 0; i < 10; i++) {
            dispatcher.poll(DIRECT, DIRECT);
            if (jdbc.queryForObject("SELECT COUNT(*) FROM doc_ingest_extract_job WHERE status IN ('QUEUED', 'RUNNING') "
                    + "AND organization_id = ?", Long.class, ORG) == 0) {
                return;
            }
        }
    }

    Map<String, List<CandidateDto>> termsByKey() {
        Map<String, List<CandidateDto>> out = new HashMap<>();
        review.list(packageId, CandidateKind.CONTRACT_TERM, null, null, 0, 200).getContent()
                .forEach(c -> out.computeIfAbsent(c.naturalKey(), k -> new ArrayList<>()).add(c));
        return out;
    }

    static CandidateDto only(List<CandidateDto> rows, UUID documentId) {
        return rows.stream().filter(c -> c.documentId().equals(documentId)).reduce((a, b) -> {
            throw new AssertionError("two candidates of one term from one document");
        }).orElseThrow();
    }

    static ContractTermCoverageDto.TermCoverage term(ContractTermCoverageDto coverage, String key) {
        return coverage.terms().stream().filter(t -> t.term().equals(key)).findFirst().orElseThrow();
    }

    String status(UUID candidateId) {
        return jdbc.queryForObject("SELECT status FROM doc_ingest_candidate WHERE id = ?", String.class, candidateId);
    }

    long riskCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM project_risk WHERE organization_id = ?", Long.class, ORG);
    }

    Long project(String name) {
        Project project = new Project();
        project.setProjectName(name);
        project.setProjectAddress("Riverside");
        project.setOrganization(organizations.findById(ORG).orElseThrow());
        return projects.save(project).getId();
    }

    String prefix(UUID doc) {
        return DocumentIngestionStorageLayout.outputPrefix(ORG, packageId, doc);
    }

    UUID pdf(String name, String... pages) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_ingest_document (id, organization_id, package_id, source_key, output_prefix, sha256, "
                + "file_name, file_type, size_bytes, status, page_count, created_at, updated_at) VALUES (?, ?, ?, 'k', ?, ?, "
                + "?, 'PDF', 1, 'PARSED', ?, now(), now())", id, ORG, packageId, prefix(id), "b".repeat(64), name,
                pages.length);
        objects.put(prefix(id) + "meta.json", ("{\"fileType\": \"PDF\", \"pageCount\": " + pages.length
                + ", \"warnings\": []}").getBytes(StandardCharsets.UTF_8));
        objects.put(prefix(id) + "pages.jsonl", String.join("\n", pages).getBytes(StandardCharsets.UTF_8));
        objects.put(prefix(id) + "tables.jsonl", new byte[0]);
        return id;
    }

    static String page(int n, String... lines) {
        StringBuilder sb = new StringBuilder("{\"page\": ").append(n).append(", \"method\": \"TEXT\", \"width\": 595.0, "
                + "\"height\": 842.0, \"lines\": [");
        for (int i = 0; i < lines.length; i++) {
            double top = 0.1 + i * 0.05;
            sb.append(i == 0 ? "" : ",").append("{\"text\": \"").append(lines[i]).append("\", \"box\": [0.1, ")
                    .append(top).append(", 0.9, ").append(top + 0.04).append("], \"ocrConfidence\": null}");
        }
        return sb.append("], \"status\": \"OK\", \"reason\": null, \"script\": \"LATIN\"}").toString();
    }

    static ChatCompletionResult ok(String content) {
        return new ChatCompletionResult(ChatCompletionOutcome.OK, 200, content, "stop", 100, 20, 120, "test-model",
                Duration.ofMillis(7), null, "{}");
    }
}
