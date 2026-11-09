package org.tornotron.echno_backend.modules.documentingestion.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
import org.tornotron.echno_backend.common.module.ModuleRegistry;
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
import org.tornotron.echno_backend.modules.documentingestion.apply.ChecklistCopier;
import org.tornotron.echno_backend.modules.documentingestion.apply.QualityRequirementApplier;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.dto.ApplyResultDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.CandidateDecision;
import org.tornotron.echno_backend.modules.documentingestion.dto.CandidateDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.CopyToTemplateRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.CopyToTemplateResultDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.DecisionOutcomeDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.ProvenanceDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.QualityRequirementEntryDto;
import org.tornotron.echno_backend.modules.documentingestion.extraction.CandidateWriter;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ClassificationWriter;
import org.tornotron.echno_backend.modules.documentingestion.extraction.classify.ClassificationExtractor;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkReader;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkRecorder;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractorRegistry;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.IngestLlmProperties;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.LlmBudget;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.LlmGate;
import org.tornotron.echno_backend.modules.documentingestion.extraction.quality.QualityFields;
import org.tornotron.echno_backend.modules.documentingestion.extraction.quality.QualityRequirementsExtractor;
import org.tornotron.echno_backend.modules.documentingestion.parsing.ParsedOutputReader;
import org.tornotron.echno_backend.modules.documentingestion.review.ProvenanceService;
import org.tornotron.echno_backend.modules.documentingestion.review.ReviewService;
import org.tornotron.echno_backend.modules.documentingestion.service.ClassificationService;
import org.tornotron.echno_backend.modules.documentingestion.service.ExtractionService;
import org.tornotron.echno_backend.modules.documentingestion.service.IngestSettingsService;
import org.tornotron.echno_backend.modules.documentingestion.service.QualityRegisterService;
import org.tornotron.echno_backend.modules.inspections.api.ChecklistTemplatePort;
import org.tornotron.echno_backend.modules.inspections.dtos.ChecklistTemplateItemRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.ChecklistTemplateRequest;
import org.tornotron.echno_backend.modules.inspections.mapper.ChecklistTemplateMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.ElementTypeMapperImpl;
import org.tornotron.echno_backend.modules.inspections.mapper.TradeMapperImpl;
import org.tornotron.echno_backend.modules.inspections.service.ChecklistTemplatePortAdapter;
import org.tornotron.echno_backend.modules.inspections.service.ChecklistTemplateService;
import org.tornotron.echno_backend.modules.inspections.service.ElementTypeService;
import org.tornotron.echno_backend.modules.inspections.service.InspectionSecurityService;
import org.tornotron.echno_backend.modules.inspections.service.TradeService;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Step 20 of the build plan, on the real migration with the LLM endpoint stubbed and the worker's outputs
 * served from a mocked object store: the done-when lines of design section 4.4. A specification is
 * classified by its heading, confirmed and read. The M25 sentence gives activity, material, grade and
 * standard with quotes, and frequency and acceptance criterion stay empty and flagged. Accepting and
 * applying fill the per-project inspection and test plan and leave the organization's checklist templates
 * untouched; only the second action copies rows into them.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@TestPropertySource(properties = {
        "echno.modules.document-ingestion.enabled=true",
        "echno.modules.document-ingestion.allowed-org-ids=" + QualityIngestionIT.ORG,
        "echno.modules.document-ingestion.llm.initial-delay-millis=86400000"
})
@Import({ExtractJobDispatcher.class, ExtractJobWorker.class, ExtractJobQueue.class, ChunkReader.class,
        ChunkRecorder.class, LlmGate.class, LlmBudget.class, IngestLlmProperties.class, LlmEndpointProperties.class,
        ExtractorRegistry.class, ClassificationExtractor.class, ClassificationWriter.class, CandidateWriter.class,
        QualityRequirementsExtractor.class, ParsedOutputReader.class, IngestSettingsService.class,
        ClassificationService.class, ExtractionService.class, ReviewService.class, ApplyService.class, ApplyGuard.class,
        QualityRequirementApplier.class, ChecklistCopier.class, QualityRegisterService.class, ProvenanceService.class,
        ChecklistTemplatePortAdapter.class, ChecklistTemplateService.class, ChecklistTemplateMapperImpl.class,
        TradeService.class, TradeMapperImpl.class, ElementTypeService.class, ElementTypeMapperImpl.class,
        TenantScopedJobRunner.class, TransactionalWorkRunner.class, TransactionRetryTemplate.class,
        SimpleMeterRegistry.class, UserContextService.class, TenantEntityHelper.class,
        AopAutoConfiguration.class, HibernateFilterConfig.class, TenantIsolationListenerRegistrar.class,
        UnscopedAccessGuard.class})
class QualityIngestionIT extends AbstractIntegrationTest {

    static final long ORG = 913_020_101L;
    static final Executor DIRECT = Runnable::run;
    static final String M25 = "4.1 Concrete shall be M25 and shall comply with IS 456:2000.";

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
    QualityRegisterService registers;

    @Autowired
    ChecklistCopier copier;

    @Autowired
    ChecklistTemplatePort checklists;

    @Autowired
    ChecklistTemplateService templates;

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

    @MockitoBean(name = "inspectionSecurity")
    InspectionSecurityService inspectionSecurity;

    @MockitoBean
    ModuleRegistry modules;

    final Map<String, byte[]> objects = new HashMap<>();
    final List<ChatCompletionRequest> sent = new ArrayList<>();
    UUID packageId;
    Long projectId;

    static final String ANSWER = """
            {"requirements": [
              {"activity": "Concrete", "material": "Concrete", "grade": "M25", "standards": ["IS 456:2000"],
               "test": "Slump", "frequency": "one sample per 50 cubic metres", "page": 1,
               "quote": "4.1 Concrete shall be M25 and shall comply with IS 456:2000."},
              {"activity": "Brick masonry", "material": "Bricks", "grade": "class designation 7.5",
               "standards": ["IS 1077:1992"], "test": "Compressive strength",
               "frequency": "10 bricks from every lot of 50,000",
               "acceptance_criterion": "the average shall be not less than 7.5 N/mm2", "page": 2,
               "quote": "5.2 Bricks shall be of class designation 7.5 conforming to IS 1077:1992.",
               "field_quotes": {"test": "Compressive strength shall be tested on 10 bricks from every lot of 50,000",
                 "frequency": "Compressive strength shall be tested on 10 bricks from every lot of 50,000",
                 "acceptance_criterion": "the average shall be not less than 7.5 N/mm2"}}
            ]}""";

    @BeforeEach
    void anOrganizationAProjectAndAnEmptyPackage() {
        jdbc.update("INSERT INTO organization (id, organization_name, organization_address, organization_email, "
                + "organization_phone) VALUES (?, ?, 'address', ?, '0000000000')", ORG, "Quality " + UUID.randomUUID(),
                UUID.randomUUID() + "@example.test");
        Project project = new Project();
        project.setProjectName("Hillview school");
        project.setProjectAddress("Hillview");
        project.setOrganization(organizations.findById(ORG).orElseThrow());
        projectId = projects.save(project).getId();
        packageId = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_ingest_package (id, organization_id, name, project_id, stage, status, created_at, "
                + "updated_at) VALUES (?, ?, 'Hillview school tender', ?, 'TENDER', 'OPEN', now(), now())", packageId, ORG,
                projectId);
        properties.setBackoff(List.of(Duration.ZERO, Duration.ZERO, Duration.ZERO));
        when(client.isAvailable()).thenReturn(true);
        when(client.complete(any())).thenAnswer(inv -> {
            ChatCompletionRequest req = inv.getArgument(0);
            if (!req.systemPrompt().equals(QualityRequirementsExtractor.PROMPT.system())) {
                return ok("{\"pages\": []}");
            }
            sent.add(req);
            return ok(ANSWER);
        });
        when(storage.readObject(any(), anyLong())).thenAnswer(inv -> Optional.ofNullable(objects.get(inv.<String>getArgument(0)))
                .map(b -> new FileStorageService.StoredObject(inv.getArgument(0), "application/json", b)));
        when(storage.objectExists(argThat(k -> k != null && k.endsWith(DocumentIngestionStorageLayout.META_FILE))))
                .thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);
        when(inspectionSecurity.canDefineChecklists()).thenReturn(true);
        when(modules.isEnabledForOrg(eq(ChecklistTemplatePort.MODULE_ID), anyLong())).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("qa", "n/a", "ROLE_USER"));
        TenantContext.setCurrentOrgId(ORG);
    }

    @AfterEach
    void removeRows() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        for (String table : List.of("doc_ingest_provenance", "doc_ingest_llm_call", "doc_ingest_source_ref",
                "doc_ingest_quality_requirement", "doc_ingest_candidate", "doc_ingest_extract_job", "doc_ingest_segment",
                "doc_ingest_document", "doc_ingest_package")) {
            jdbc.update("DELETE FROM " + table + " WHERE organization_id = ?", ORG);
        }
        jdbc.update("DELETE FROM checklist_template_items WHERE template_id IN (SELECT id FROM checklist_templates "
                + "WHERE organization_id = ?)", ORG);
        for (String table : List.of("checklist_templates", "inspection_trades", "project")) {
            jdbc.update("DELETE FROM " + table + " WHERE organization_id = ?", ORG);
        }
        jdbc.update("DELETE FROM organization WHERE id = ?", ORG);
    }

    @Test
    void theM25SentenceGivesItsValuesWithQuotesAndTheTemplateIsUntouchedUntilTheSecondAction() {
        classifyAndRead(specificationPdf());

        assertThat(sent).as("one call for the two pages").hasSize(1);
        Map<String, CandidateDto> byKey = candidates();
        assertThat(byKey).containsOnlyKeys("Concrete", "Bricks");
        CandidateDto concrete = byKey.get("Concrete");
        assertThat(concrete.payload()).containsEntry(QualityFields.ACTIVITY, "Concrete")
                .containsEntry(QualityFields.MATERIAL, "Concrete").containsEntry(QualityFields.GRADE, "M25")
                .containsEntry(QualityFields.STANDARDS, List.of("IS 456:2000"))
                .as("no test, frequency or acceptance criterion the sentence does not print")
                .doesNotContainKeys(QualityFields.TEST, QualityFields.FREQUENCY, QualityFields.ACCEPTANCE_CRITERION);
        assertThat(concrete.sources()).hasSize(4).allSatisfy(s -> {
            assertThat(s.quote()).isEqualTo(M25);
            assertThat(s.quoteVerified()).isTrue();
            assertThat(s.page()).isEqualTo(1);
            assertThat(s.box()).isNotNull();
        });
        assertThat(concrete.flags()).extracting(f -> f.code()).containsExactlyInAnyOrder(
                QualityFields.FREQUENCY_NOT_STATED, QualityFields.ACCEPTANCE_NOT_STATED);
        assertThat(byKey.get("Bricks").payload()).containsEntry(QualityFields.FREQUENCY,
                "10 bricks from every lot of 50,000");
        long itemsBefore = templateItems();
        assertThat(templateCount()).isZero();

        List<DecisionOutcomeDto> outcomes = review.decide(packageId, byKey.values().stream()
                .map(c -> new CandidateDecision(c.id(), CandidateDecision.Action.ACCEPT, null,
                        c.flags().stream().map(f -> f.code()).toList())).toList());
        assertThat(outcomes).allSatisfy(o -> assertThat(o.outcome()).isEqualTo(DecisionOutcomeDto.Outcome.ACCEPTED));
        assertThat(templateCount()).as("accepting writes no template").isZero();
        assertThat(rowCount()).as("nor the plan").isZero();

        ApplyResultDto applied = apply.apply(packageId, CandidateKind.QUALITY_REQUIREMENT);

        assertThat(applied.applied()).isEqualTo(2);
        assertThat(applied.failed()).isZero();
        assertThat(templateCount()).as("applying writes no template either").isZero();
        assertThat(templateItems()).isEqualTo(itemsBefore);
        List<QualityRequirementEntryDto> plan = registers.forProject(projectId, 0, 50).getContent();
        assertThat(plan).hasSize(2);
        QualityRequirementEntryDto concreteRow = row(plan, "Concrete");
        assertThat(concreteRow.grade()).isEqualTo("M25");
        assertThat(concreteRow.standards()).containsExactly("IS 456:2000");
        assertThat(concreteRow.frequency()).isNull();
        assertThat(concreteRow.acceptanceCriterion()).isNull();
        assertThat(concreteRow.notStated()).containsExactly(QualityFields.FREQUENCY, QualityFields.ACCEPTANCE_CRITERION);
        assertThat(concreteRow.tradeCode()).as("matched to the organization's trades at apply").isEqualTo("rcc");
        assertThat(concreteRow.sourceClass()).isEqualTo("SPECIFICATION");
        assertThat(concreteRow.citations()).isNotEmpty().allSatisfy(s -> assertThat(s.quote()).isEqualTo(M25));
        QualityRequirementEntryDto bricksRow = row(plan, "Bricks");
        assertThat(bricksRow.tradeCode()).isEqualTo("masonry");
        assertThat(bricksRow.notStated()).isEmpty();
        assertThat(registers.forPackage(packageId, 0, 50).getTotalElements()).isEqualTo(2);
        assertThat(provenance.forTarget(QualityRequirementApplier.TARGET, concreteRow.id().toString()))
                .extracting(ProvenanceDto::targetField)
                .containsExactlyInAnyOrder("activity", "material", "grade", "standards");

        CopyToTemplateResultDto copied = copier.copy(new CopyToTemplateRequest(
                List.of(concreteRow.id(), bricksRow.id()), null));

        assertThat(copied.templates()).hasSize(2).allSatisfy(t -> {
            assertThat(t.created()).isTrue();
            assertThat(t.itemsAdded()).isEqualTo(1);
            assertThat(t.active()).isTrue();
        });
        assertThat(copied.rows()).allSatisfy(r -> assertThat(r.outcome()).isEqualTo(CopyToTemplateResultDto.Outcome.COPIED));
        assertThat(templateItems()).isEqualTo(itemsBefore + 2);
        Map<String, Object> item = jdbc.queryForMap("SELECT i.id, i.category, i.check_point, i.expected_value, "
                + "i.acceptance_criterion, i.specification FROM checklist_template_items i JOIN checklist_templates t "
                + "ON t.id = i.template_id JOIN inspection_trades tr ON tr.id = t.trade_id WHERE t.organization_id = ? "
                + "AND tr.code = 'rcc'", ORG);
        assertThat(item).containsEntry("category", "Concrete").containsEntry("check_point", "Concrete")
                .containsEntry("expected_value", "M25").containsEntry("specification", "Standards: IS 456:2000");
        assertThat(item.get("acceptance_criterion")).as("no acceptance criterion invented").isNull();
        assertThat(provenance.forTarget(ChecklistCopier.TARGET_ITEM, item.get("id").toString()))
                .extracting(ProvenanceDto::targetField).contains("category", "checkPoint", "expectedValue", "specification")
                .doesNotContain("acceptanceCriterion");
        assertThat(registers.forProject(projectId, 0, 50).getContent()).allSatisfy(r -> {
            assertThat(r.templateId()).isNotNull();
            assertThat(r.templateItemId()).isNotNull();
            assertThat(r.templateVersion()).isEqualTo(1);
        });

        CopyToTemplateResultDto again = copier.copy(new CopyToTemplateRequest(List.of(concreteRow.id()), null));
        assertThat(again.templates()).isEmpty();
        assertThat(again.rows()).singleElement()
                .satisfies(r -> assertThat(r.outcome()).isEqualTo(CopyToTemplateResultDto.Outcome.ALREADY_COPIED));
        assertThat(templateItems()).isEqualTo(itemsBefore + 2);
    }

    @Test
    void anExistingTemplateKeepsItsCheckPointsAndGetsTheRowAtTheEndInANewRevision() {
        UUID templateId = templates.create(new ChecklistTemplateRequest("rcc", null, "Concrete pour checklist", null,
                null, null, true, List.of(new ChecklistTemplateItemRequest("Formwork", "Formwork is clean and oiled",
                        null, null, null, null, false, "medium")))).id();
        UUID firstItem = jdbc.queryForObject("SELECT id FROM checklist_template_items WHERE template_id = ?", UUID.class,
                templateId);
        classifyAndRead(specificationPdf());
        acceptAllAndApply();
        UUID concrete = row(registers.forPackage(packageId, 0, 50).getContent(), "Concrete").id();

        CopyToTemplateResultDto copied = copier.copy(new CopyToTemplateRequest(List.of(concrete), null));

        assertThat(copied.templates()).singleElement().satisfies(t -> {
            assertThat(t.templateId()).isEqualTo(templateId);
            assertThat(t.created()).isFalse();
            assertThat(t.version()).isEqualTo(2);
            assertThat(t.templateName()).isEqualTo("Concrete pour checklist");
        });
        List<Map<String, Object>> items = jdbc.queryForList("SELECT id, check_point, line_order FROM "
                + "checklist_template_items WHERE template_id = ? ORDER BY line_order", templateId);
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("id", firstItem).containsEntry("check_point", "Formwork is clean and oiled");
        assertThat(items.get(1)).containsEntry("check_point", "Concrete");
        assertThat(((Number) items.get(1).get("line_order")).intValue()).isEqualTo(1);
    }

    @Test
    void theCopyRefusesWhenInspectionsAreOffOrTheRoleCannotDefineChecklistsAndANamedTradeOverridesTheMatch() {
        when(modules.isEnabledForOrg(eq(ChecklistTemplatePort.MODULE_ID), anyLong())).thenReturn(false);
        classifyAndRead(specificationPdf());
        acceptAllAndApply();
        List<QualityRequirementEntryDto> plan = registers.forPackage(packageId, 0, 50).getContent();
        assertThat(plan).as("the plan is written with inspections off, with no trade").hasSize(2)
                .allSatisfy(r -> assertThat(r.tradeId()).isNull());
        List<UUID> ids = plan.stream().map(QualityRequirementEntryDto::id).toList();

        assertThatThrownBy(() -> copier.copy(new CopyToTemplateRequest(ids, null)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Site inspections are not enabled for this organization");
        assertThat(templateCount()).isZero();

        when(modules.isEnabledForOrg(eq(ChecklistTemplatePort.MODULE_ID), anyLong())).thenReturn(true);
        when(inspectionSecurity.canDefineChecklists()).thenReturn(false);
        assertThatThrownBy(() -> copier.copy(new CopyToTemplateRequest(ids, null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(templateCount()).isZero();

        when(inspectionSecurity.canDefineChecklists()).thenReturn(true);
        CopyToTemplateResultDto noTrade = copier.copy(new CopyToTemplateRequest(ids, null));
        assertThat(noTrade.rows()).allSatisfy(r -> assertThat(r.outcome())
                .isEqualTo(CopyToTemplateResultDto.Outcome.NO_TRADE));
        assertThat(templateCount()).isZero();

        UUID masonry = checklists.activeTrades().stream().filter(t -> t.code().equals("masonry")).findFirst()
                .orElseThrow().id();
        CopyToTemplateResultDto named = copier.copy(new CopyToTemplateRequest(ids, masonry));
        assertThat(named.templates()).singleElement().satisfies(t -> {
            assertThat(t.tradeId()).isEqualTo(masonry);
            assertThat(t.itemsAdded()).isEqualTo(2);
        });
        assertThat(templateItems()).isEqualTo(2);

        assertThatThrownBy(() -> copier.copy(new CopyToTemplateRequest(ids, UUID.randomUUID())))
                .hasMessageContaining("is not a trade of this organization");
    }

    // --------------------------------------------------------------------------------------------
    // Fixtures
    // --------------------------------------------------------------------------------------------

    UUID specificationPdf() {
        return pdf("Specification.pdf",
                page(1, "TECHNICAL SPECIFICATIONS", M25),
                page(2, "5.2 Bricks shall be of class designation 7.5 conforming to IS 1077:1992.",
                        "5.3 Compressive strength shall be tested on 10 bricks from every lot of 50,000 and the average",
                        "shall be not less than 7.5 N/mm2."));
    }

    void classifyAndRead(UUID... docs) {
        for (UUID doc : docs) {
            jdbc.update("INSERT INTO doc_ingest_extract_job (id, organization_id, package_id, document_id, extractor_key, "
                    + "extractor_version, status, queued_at, created_at, updated_at) VALUES (gen_random_uuid(), ?, ?, ?, "
                    + "'classify', '1', 'QUEUED', now(), now(), now())", ORG, packageId, doc);
        }
        runAll();
        classification.list(packageId, 0, 50).getContent().forEach(d ->
                assertThat(d.docClass()).as("classified by its heading").isEqualTo("SPECIFICATION"));
        classification.confirmAll(packageId);
        runAll();
    }

    void acceptAllAndApply() {
        review.decide(packageId, candidates().values().stream()
                .map(c -> new CandidateDecision(c.id(), CandidateDecision.Action.ACCEPT, null,
                        c.flags().stream().map(f -> f.code()).toList())).toList());
        assertThat(apply.apply(packageId, CandidateKind.QUALITY_REQUIREMENT).applied()).isEqualTo(2);
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

    Map<String, CandidateDto> candidates() {
        Map<String, CandidateDto> out = new HashMap<>();
        review.list(packageId, CandidateKind.QUALITY_REQUIREMENT, null, null, 0, 200).getContent()
                .forEach(c -> assertThat(out.put(c.naturalKey(), c)).as("one candidate per key").isNull());
        return out;
    }

    static QualityRequirementEntryDto row(List<QualityRequirementEntryDto> plan, String subject) {
        return plan.stream().filter(r -> r.subject().equals(subject)).findFirst().orElseThrow();
    }

    long templateCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM checklist_templates WHERE organization_id = ?", Long.class, ORG);
    }

    long templateItems() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM checklist_template_items i JOIN checklist_templates t ON "
                + "t.id = i.template_id WHERE t.organization_id = ?", Long.class, ORG);
    }

    long rowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM doc_ingest_quality_requirement WHERE organization_id = ?",
                Long.class, ORG);
    }

    String prefix(UUID doc) {
        return DocumentIngestionStorageLayout.outputPrefix(ORG, packageId, doc);
    }

    UUID pdf(String name, String... pages) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_ingest_document (id, organization_id, package_id, source_key, output_prefix, sha256, "
                + "file_name, file_type, size_bytes, status, page_count, created_at, updated_at) VALUES (?, ?, ?, 'k', ?, ?, "
                + "?, 'PDF', 1, 'PARSED', ?, now(), now())", id, ORG, packageId, prefix(id), "c".repeat(64), name,
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
