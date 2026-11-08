package org.tornotron.echno_backend.modules.documentingestion.job;

import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionStorageLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.history.StatusTransitionRecorder;
import org.tornotron.echno_backend.common.history.mapper.StatusTransitionMapper;
import org.tornotron.echno_backend.common.llm.ChatCompletionClient;
import org.tornotron.echno_backend.common.llm.ChatCompletionOutcome;
import org.tornotron.echno_backend.common.llm.ChatCompletionRequest;
import org.tornotron.echno_backend.common.llm.ChatCompletionResult;
import org.tornotron.echno_backend.common.llm.LlmEndpointProperties;
import org.tornotron.echno_backend.common.mapper.AttachmentMapper;
import org.tornotron.echno_backend.common.multitenancy.HibernateFilterConfig;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.multitenancy.TenantIsolationListenerRegistrar;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.common.multitenancy.UnscopedAccessGuard;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.mapper.EmployeeMapper;
import org.tornotron.echno_backend.modules.documentingestion.apply.ApplyGuard;
import org.tornotron.echno_backend.modules.documentingestion.apply.ApplyService;
import org.tornotron.echno_backend.modules.documentingestion.apply.MilestoneApplier;
import org.tornotron.echno_backend.modules.documentingestion.apply.ProjectFieldApplier;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocClass;
import org.tornotron.echno_backend.modules.documentingestion.dto.ApplyResultDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.CandidateDecision;
import org.tornotron.echno_backend.modules.documentingestion.dto.CandidateDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.ClassificationRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.DocumentClassificationDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.ProvenanceDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.TenderProfileDto;
import org.tornotron.echno_backend.modules.documentingestion.extraction.CandidateWriter;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ClassificationWriter;
import org.tornotron.echno_backend.modules.documentingestion.extraction.classify.ClassificationExtractor;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkReader;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkRecorder;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractorRegistry;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.IngestLlmProperties;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.LlmBudget;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.LlmGate;
import org.tornotron.echno_backend.modules.documentingestion.extraction.milestone.MilestoneExtractor;
import org.tornotron.echno_backend.modules.documentingestion.extraction.milestone.MilestoneTables;
import org.tornotron.echno_backend.modules.documentingestion.extraction.project.ProjectSetupExtractor;
import org.tornotron.echno_backend.modules.documentingestion.parsing.ParsedOutputReader;
import org.tornotron.echno_backend.modules.documentingestion.review.ProvenanceService;
import org.tornotron.echno_backend.modules.documentingestion.review.ReviewService;
import org.tornotron.echno_backend.modules.documentingestion.service.ClassificationService;
import org.tornotron.echno_backend.modules.documentingestion.service.ExtractionService;
import org.tornotron.echno_backend.modules.documentingestion.service.IngestPackageService;
import org.tornotron.echno_backend.modules.documentingestion.service.IngestSettingsService;
import org.tornotron.echno_backend.modules.workprogress.api.ContractBoqPort;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.project.ProjectService;
import org.tornotron.echno_backend.project.dto.ProjectSimpleDto;
import org.tornotron.echno_backend.project.mapper.ProjectMapper;
import org.tornotron.echno_backend.subcontract.SubContract;
import org.tornotron.echno_backend.subcontract.SubContractRepository;
import org.tornotron.echno_backend.subcontract.SubContractService;
import org.tornotron.echno_backend.subcontract.mapper.SubContractMapperImpl;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;
import org.tornotron.echno_backend.wbs.WbsElementService;
import org.tornotron.echno_backend.wbs.WbsScheduleClock;
import org.tornotron.echno_backend.wbs.mapper.WbsElementMapperImpl;

/**
 * Step 13 of the build plan, on the real migration with the LLM endpoint stubbed and the worker's outputs
 * served from a mocked object store: the done-when lines of design sections 4.1 and 4.6. A package with a
 * NIT, a GCC and a BOQ is classified, a person corrects one class and confirms, the NIT is read into project
 * fields with citations, and accepting them creates one project with provenance on each field. A milestone
 * workbook is read with no LLM into milestones that land on the schedule and on the contract.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@TestPropertySource(properties = {
        "echno.modules.document-ingestion.enabled=true",
        "echno.modules.document-ingestion.allowed-org-ids=" + ProjectAndMilestoneIngestionIT.ORG,
        "echno.modules.document-ingestion.llm.initial-delay-millis=86400000"
})
@Import({ExtractJobDispatcher.class, ExtractJobWorker.class, ExtractJobQueue.class, ChunkReader.class,
        ChunkRecorder.class, LlmGate.class, LlmBudget.class, IngestLlmProperties.class, LlmEndpointProperties.class,
        ExtractorRegistry.class, ClassificationExtractor.class, ClassificationWriter.class, CandidateWriter.class,
        ProjectSetupExtractor.class, MilestoneExtractor.class, ParsedOutputReader.class, IngestSettingsService.class,
        ClassificationService.class, ExtractionService.class, ReviewService.class, ApplyService.class, ApplyGuard.class,
        ProjectFieldApplier.class, MilestoneApplier.class, ProvenanceService.class, IngestPackageService.class,
        ProjectService.class, WbsElementService.class, WbsElementMapperImpl.class, SubContractService.class,
        SubContractMapperImpl.class, ProjectAndMilestoneIngestionIT.Clocks.class,
        TenantScopedJobRunner.class, TransactionalWorkRunner.class, TransactionRetryTemplate.class,
        SimpleMeterRegistry.class, UserContextService.class, TenantEntityHelper.class,
        AopAutoConfiguration.class, HibernateFilterConfig.class, TenantIsolationListenerRegistrar.class,
        UnscopedAccessGuard.class})
class ProjectAndMilestoneIngestionIT extends AbstractIntegrationTest {

    static final long ORG = 913_013_101L;
    static final Executor DIRECT = Runnable::run;

    @TestConfiguration
    static class Clocks {
        @Bean
        @WbsScheduleClock
        Clock wbsScheduleClock() {
            return Clock.system(ZoneId.of("Asia/Kolkata"));
        }
    }

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
    IngestPackageService packages;

    @Autowired
    IngestLlmProperties properties;

    @Autowired
    OrganizationRepository organizations;

    @Autowired
    ProjectRepository projects;

    @Autowired
    SubContractRepository subContracts;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    ChatCompletionClient client;

    @MockitoBean
    FileStorageService storage;

    @MockitoBean(name = "orgSecurity")
    OrganizationSecurityService orgSecurity;

    @MockitoBean
    ContractBoqPort contractBoq;

    @MockitoBean
    ProjectMapper projectMapper;

    @MockitoBean
    EmployeeMapper employeeMapper;

    @MockitoBean
    AttachmentService attachmentService;

    @MockitoBean
    AttachmentMapper attachmentMapper;

    @MockitoBean
    StatusTransitionRecorder statusTransitionRecorder;

    @MockitoBean
    StatusTransitionMapper statusTransitionMapper;

    @MockitoBean
    PayloadValidator payloadValidator;

    final Map<String, byte[]> objects = new HashMap<>();
    final List<ChatCompletionRequest> sent = new ArrayList<>();
    UUID packageId;

    @BeforeEach
    void anOrganizationAndAnEmptyPackage() {
        jdbc.update("INSERT INTO organization (id, organization_name, organization_address, organization_email, "
                + "organization_phone) VALUES (?, ?, 'address', ?, '0000000000')", ORG, "Ingest " + UUID.randomUUID(),
                UUID.randomUUID() + "@example.test");
        packageId = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_ingest_package (id, organization_id, name, stage, status, created_at, updated_at) "
                + "VALUES (?, ?, 'Hillview tender', 'TENDER', 'OPEN', now(), now())", packageId, ORG);
        properties.setBackoff(List.of(Duration.ZERO, Duration.ZERO, Duration.ZERO));
        when(client.isAvailable()).thenReturn(true);
        when(client.complete(any())).thenAnswer(inv -> {
            ChatCompletionRequest req = inv.getArgument(0);
            sent.add(req);
            return ok(req.systemPrompt().equals(ProjectSetupExtractor.PROMPT.system()) ? NIT_ANSWER : "{\"pages\": []}");
        });
        when(storage.readObject(any(), anyLong())).thenAnswer(inv -> Optional.ofNullable(objects.get(inv.<String>getArgument(0)))
                .map(b -> new FileStorageService.StoredObject(inv.getArgument(0), "application/json", b)));
        when(storage.objectExists(argThat(k -> k != null && k.endsWith(DocumentIngestionStorageLayout.META_FILE))))
                .thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);
        when(projectMapper.toSimpleDto(any())).thenAnswer(inv -> {
            ProjectSimpleDto dto = new ProjectSimpleDto();
            dto.setId(inv.<Project>getArgument(0).getId());
            return dto;
        });
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("pm", "n/a", "ROLE_USER"));
        TenantContext.setCurrentOrgId(ORG);
    }

    @AfterEach
    void removeRows() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        for (String table : List.of("doc_ingest_provenance", "doc_ingest_llm_call", "doc_ingest_source_ref",
                "doc_ingest_candidate", "doc_ingest_extract_job", "doc_ingest_segment", "doc_ingest_document",
                "doc_ingest_tender_profile", "doc_ingest_package", "contract_milestone", "sub_contract", "wbs_element",
                "project")) {
            jdbc.update("DELETE FROM " + table + " WHERE organization_id = ?", ORG);
        }
        jdbc.update("DELETE FROM organization WHERE id = ?", ORG);
    }

    // --------------------------------------------------------------------------------------------
    // Section 4.1: documents to project setup
    // --------------------------------------------------------------------------------------------

    static final String NIT_ANSWER = """
            {"fields": [
              {"field": "project_name", "value": "Library block, Hillview", "page": 1, "quote": "Name of Work: Library block, Hillview"},
              {"field": "location", "value": "Hillview Campus, Northfield", "page": 1, "quote": "Location: Hillview Campus, Northfield"},
              {"field": "estimated_value", "value": "Rs. 4,20,00,000", "page": 1, "quote": "Estimated Cost: Rs. 4,20,00,000"},
              {"field": "emd", "value": "Rs. 8,40,000", "page": 2, "quote": "Earnest Money Deposit: Rs. 8,40,000"},
              {"field": "bid_due_date", "value": "25.11.2026 at 15:00 hrs", "page": 2, "quote": "Last date of submission: 25.11.2026 at 15:00 hrs"},
              {"field": "duration", "value": "18 months", "page": 2, "quote": "Time for completion: 18 months"},
              {"field": "client", "value": "Northfield Education Trust", "page": 1, "quote": "invites bids from the Northfield Trust"},
              {"field": "tender_fee", "value": "Rs. 9,999", "page": 2, "quote": "Tender fee: Rs. 5,000"}
            ]}""";

    @Test
    void aTenderPackageBecomesOneProjectWithASourceOnEveryField() {
        UUID nit = pdf("Tender notice.pdf",
                page(1, "NOTICE INVITING TENDER", "Name of Work: Library block, Hillview",
                        "Location: Hillview Campus, Northfield", "Estimated Cost: Rs. 4,20,00,000"),
                page(2, "Earnest Money Deposit: Rs. 8,40,000", "Last date of submission: 25.11.2026 at 15:00 hrs",
                        "Time for completion: 18 months", "Tender fee: Rs. 5,000"));
        UUID gcc = pdf("Conditions.pdf", page(1, "GENERAL CONDITIONS OF CONTRACT", "1. Definitions"));
        UUID boq = workbook("BOQ.xlsx", "BOQ");
        classifyAll(nit, gcc, boq);

        Map<UUID, String> classes = new HashMap<>();
        classification.list(packageId, 0, 50).getContent().forEach(d -> classes.put(d.documentId(), d.docClass()));
        assertThat(classes).containsEntry(nit, "NIT").containsEntry(gcc, "GCC").containsEntry(boq, "BOQ");

        DocumentClassificationDto corrected = classification.confirm(gcc, new ClassificationRequest(DocClass.SCC, List.of()));
        assertThat(corrected.docClass()).isEqualTo("SCC");
        classification.confirmAll(packageId);
        runAll();

        assertThat(sent).as("only the NIT goes to the project reader").hasSize(1);
        Map<String, CandidateDto> fields = new HashMap<>();
        candidates(CandidateKind.PROJECT_FIELD).forEach(c -> fields.put(c.naturalKey(), c));
        assertThat(fields).containsKeys("project_name", "location", "estimated_value", "emd", "bid_due_date", "duration",
                "client");
        assertThat(fields).as("a number not in its quote is dropped").doesNotContainKey("tender_fee");
        CandidateDto name = fields.get("project_name");
        assertThat(name.sources()).singleElement().satisfies(s -> {
            assertThat(s.page()).isEqualTo(1);
            assertThat(s.quoteVerified()).isTrue();
            assertThat(s.box()).isNotNull();
            assertThat(s.documentId()).isEqualTo(nit);
        });
        assertThat(fields.get("client").flags()).extracting(f -> f.code()).contains("QUOTE_NOT_FOUND");

        long projectsBefore = projectCount();
        List<CandidateDecision> decisions = new ArrayList<>();
        fields.values().stream().filter(c -> !c.naturalKey().equals("client"))
                .forEach(c -> decisions.add(new CandidateDecision(c.id(), CandidateDecision.Action.ACCEPT, null, null)));
        review.decide(packageId, decisions);
        assertThat(projectCount()).as("accepting writes nothing live").isEqualTo(projectsBefore);

        ApplyResultDto result = apply.apply(packageId, CandidateKind.PROJECT_FIELD);

        assertThat(result.applied()).isEqualTo(6);
        assertThat(result.failed()).isZero();
        assertThat(projectCount()).isEqualTo(projectsBefore + 1);
        Long projectId = jdbc.queryForObject("SELECT project_id FROM doc_ingest_package WHERE id = ?", Long.class, packageId);
        Project project = inTenant(() -> projects.findByIdAndOrganization_Id(projectId, ORG).orElseThrow());
        assertThat(project.getProjectName()).isEqualTo("Library block, Hillview");
        assertThat(project.getProjectAddress()).isEqualTo("Hillview Campus, Northfield");
        assertThat(project.getStatus().name()).isEqualTo("upcoming");

        TenderProfileDto profile = packages.tenderProfile(packageId);
        assertThat(profile.projectId()).isEqualTo(projectId);
        assertThat(profile.estimatedValue()).isEqualByComparingTo("42000000");
        assertThat(profile.emdAmount()).isEqualByComparingTo("840000");
        assertThat(profile.bidDueAt()).isEqualTo(LocalDateTime.of(2026, 11, 25, 15, 0));
        assertThat(profile.durationText()).isEqualTo("18 months");

        List<ProvenanceDto> onProject = provenance.forTarget(ProjectFieldApplier.TARGET_PROJECT, projectId.toString());
        assertThat(onProject).extracting(ProvenanceDto::targetField).containsExactlyInAnyOrder("projectName", "projectAddress");
        assertThat(onProject).allSatisfy(p -> assertThat(p.source()).isNotNull());
        assertThat(provenance.forTarget(ProjectFieldApplier.TARGET_PROFILE, profile.id().toString()))
                .extracting(ProvenanceDto::targetField)
                .containsExactlyInAnyOrder("estimatedValue", "emdAmount", "bidDueAt", "durationText");
    }

    @Test
    void rejectingTheProjectFieldsCreatesNothing() {
        UUID nit = pdf("Tender notice.pdf",
                page(1, "NOTICE INVITING TENDER", "Name of Work: Library block, Hillview",
                        "Location: Hillview Campus, Northfield", "Estimated Cost: Rs. 4,20,00,000"),
                page(2, "Earnest Money Deposit: Rs. 8,40,000", "Last date of submission: 25.11.2026 at 15:00 hrs",
                        "Time for completion: 18 months", "Tender fee: Rs. 5,000"));
        classifyAll(nit);
        classification.confirmAll(packageId);
        runAll();
        long before = projectCount();

        review.decide(packageId, candidates(CandidateKind.PROJECT_FIELD).stream()
                .map(c -> new CandidateDecision(c.id(), CandidateDecision.Action.REJECT, null, null)).toList());
        ApplyResultDto result = apply.apply(packageId, CandidateKind.PROJECT_FIELD);

        assertThat(result.applied()).isZero();
        assertThat(projectCount()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM doc_ingest_tender_profile WHERE organization_id = ?",
                Long.class, ORG)).isZero();
    }

    @Test
    void aNameWithNoLocationIsRefusedBeforeAnyRowAndTwoValuesForOneFieldAreRefused() {
        UUID nit = pdf("Tender notice.pdf",
                page(1, "NOTICE INVITING TENDER", "Name of Work: Library block, Hillview",
                        "Location: Hillview Campus, Northfield", "Estimated Cost: Rs. 4,20,00,000"),
                page(2, "Earnest Money Deposit: Rs. 8,40,000"));
        classifyAll(nit);
        classification.confirmAll(packageId);
        runAll();
        Map<String, CandidateDto> fields = new HashMap<>();
        candidates(CandidateKind.PROJECT_FIELD).forEach(c -> fields.put(c.naturalKey(), c));
        review.decide(packageId, List.of(new CandidateDecision(fields.get("project_name").id(),
                CandidateDecision.Action.ACCEPT, null, null)));

        assertThatThrownBy(() -> apply.apply(packageId, CandidateKind.PROJECT_FIELD))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("name and its location");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM doc_ingest_candidate WHERE organization_id = ? AND status = "
                + "'ACCEPTED'", Long.class, ORG)).isEqualTo(1);
    }

    // --------------------------------------------------------------------------------------------
    // Section 4.6: milestone intelligence
    // --------------------------------------------------------------------------------------------

    @Test
    void theFiveRowScheduleGivesFiveScheduleMilestonesAtTheRightDatesAndFiveContractMilestones() {
        Long projectId = project("Hillview library", LocalDateTime.of(2027, 1, 1, 0, 0));
        Long contractId = contract(projectId, "Main civil works");
        jdbc.update("UPDATE doc_ingest_package SET project_id = ?, target_sub_contract_id = ? WHERE id = ?", projectId,
                contractId, packageId);
        UUID schedule = workbook("Payment schedule.xlsx", "Milestones", fiveRows("15%"));
        classifyAll(schedule);
        classification.confirm(schedule, new ClassificationRequest(DocClass.MILESTONE_SCHEDULE, List.of()));
        runAll();

        List<CandidateDto> milestones = candidates(CandidateKind.MILESTONE);
        assertThat(sent).as("a milestone table needs no LLM").isEmpty();
        assertThat(milestones).hasSize(5);
        assertThat(milestones).allSatisfy(m -> {
            assertThat(m.flags()).extracting(f -> f.code()).doesNotContain(MilestoneTables.PERCENT_TOTAL);
            assertThat(m.sources()).allSatisfy(s -> assertThat(s.sheet()).isEqualTo("Milestones"));
        });
        CandidateDto first = milestones.stream().filter(m -> m.naturalKey().equals("Mobilisation and site set-up"))
                .findFirst().orElseThrow();
        assertThat(first.payload()).containsEntry(MilestoneTables.TIME, "Month 1").containsEntry(MilestoneTables.PERCENT, "10%");
        assertThat(first.sources()).filteredOn(s -> s.field().equals(MilestoneTables.TIME))
                .singleElement().satisfies(s -> assertThat(s.cell()).isEqualTo("C2"));

        review.decide(packageId, milestones.stream().map(m -> new CandidateDecision(m.id(), CandidateDecision.Action.ACCEPT,
                null, m.flags().stream().map(f -> f.code()).toList())).toList());
        ApplyResultDto result = apply.apply(packageId, CandidateKind.MILESTONE);

        assertThat(result.applied()).as("%s; %s", result, jdbc.queryForList("SELECT status || ': ' || COALESCE(apply_error, '') "
                + "FROM doc_ingest_candidate WHERE organization_id = ? AND kind = 'MILESTONE'", String.class, ORG))
                .isEqualTo(5);
        assertThat(jdbc.queryForList("SELECT end_date FROM wbs_element WHERE project_id = ? AND is_milestone ORDER BY "
                + "sort_order", LocalDate.class, projectId)).containsExactly(LocalDate.of(2027, 2, 1),
                LocalDate.of(2027, 4, 1), LocalDate.of(2027, 7, 1), LocalDate.of(2027, 10, 1), LocalDate.of(2028, 1, 1));
        assertThat(jdbc.queryForList("SELECT payment_percentage FROM contract_milestone WHERE sub_contract_id = ? ORDER BY "
                + "target_date", BigDecimal.class, contractId)).extracting(BigDecimal::intValue)
                .containsExactly(10, 20, 30, 25, 15);
        String wbsId = jdbc.queryForObject("SELECT id::STRING FROM wbs_element WHERE project_id = ? AND title = ?",
                String.class, projectId, "Mobilisation and site set-up");
        assertThat(provenance.forTarget(MilestoneApplier.TARGET_WBS, wbsId)).extracting(ProvenanceDto::targetField)
                .containsExactlyInAnyOrder("title", "endDate");
        String milestoneId = jdbc.queryForObject("SELECT id::STRING FROM contract_milestone WHERE sub_contract_id = ? AND "
                + "name = ?", String.class, contractId, "Mobilisation and site set-up");
        assertThat(provenance.forTarget(MilestoneApplier.TARGET_CONTRACT_MILESTONE, milestoneId))
                .extracting(ProvenanceDto::targetField).containsExactlyInAnyOrder("name", "targetDate", "paymentPercentage");
    }

    @Test
    void percentagesTotalling95AreFlaggedOnEveryRow() {
        UUID schedule = workbook("Payment schedule.xlsx", "Milestones", fiveRows("10%"));
        classifyAll(schedule);
        classification.confirm(schedule, new ClassificationRequest(DocClass.MILESTONE_SCHEDULE, List.of()));
        runAll();

        assertThat(candidates(CandidateKind.MILESTONE)).hasSize(5).allSatisfy(m -> assertThat(m.flags())
                .anySatisfy(f -> {
                    assertThat(f.code()).isEqualTo(MilestoneTables.PERCENT_TOTAL);
                    assertThat(f.message()).contains("95");
                }));
    }

    @Test
    void aMilestoneWithNoProjectIsRefusedAndAMonthWithNoStartDateStaysEmpty() {
        UUID schedule = workbook("Payment schedule.xlsx", "Milestones", fiveRows("15%"));
        classifyAll(schedule);
        classification.confirm(schedule, new ClassificationRequest(DocClass.MILESTONE_SCHEDULE, List.of()));
        runAll();
        List<CandidateDto> milestones = candidates(CandidateKind.MILESTONE);
        assertThat(milestones).allSatisfy(m -> assertThat(m.flags()).extracting(f -> f.code())
                .contains(MilestoneTables.RELATIVE_TIME));
        review.decide(packageId, milestones.stream().map(m -> new CandidateDecision(m.id(), CandidateDecision.Action.ACCEPT,
                null, m.flags().stream().map(f -> f.code()).toList())).toList());

        assertThatThrownBy(() -> apply.apply(packageId, CandidateKind.MILESTONE))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("no project");

        Long projectId = project("Hillview annexe", null);
        jdbc.update("UPDATE doc_ingest_package SET project_id = ? WHERE id = ?", projectId, packageId);
        assertThat(apply.apply(packageId, CandidateKind.MILESTONE).applied()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wbs_element WHERE project_id = ? AND is_milestone AND "
                + "end_date IS NULL AND description = 'As printed: Month 1'", Long.class, projectId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract_milestone WHERE organization_id = ?", Long.class, ORG))
                .as("no sub-contract, no payment milestone").isZero();
    }

    // --------------------------------------------------------------------------------------------
    // Fixtures
    // --------------------------------------------------------------------------------------------

    static String[][] fiveRows(String lastPercent) {
        return new String[][] {
                {"S.No", "Milestone", "Time from start", "Payment %"},
                {"1", "Mobilisation and site set-up", "Month 1", "10%"},
                {"2", "Foundations complete", "Month 3", "20%"},
                {"3", "Superstructure complete", "Month 6", "30%"},
                {"4", "Finishes complete", "Month 9", "25%"},
                {"5", "Handover", "Month 12", lastPercent}};
    }

    void classifyAll(UUID... docs) {
        for (UUID doc : docs) {
            jdbc.update("INSERT INTO doc_ingest_extract_job (id, organization_id, package_id, document_id, extractor_key, "
                    + "extractor_version, status, queued_at, created_at, updated_at) VALUES (gen_random_uuid(), ?, ?, ?, "
                    + "'classify', '1', 'QUEUED', now(), now(), now())", ORG, packageId, doc);
        }
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

    List<CandidateDto> candidates(CandidateKind kind) {
        return review.list(packageId, kind, CandidateStatus.PROPOSED, null, 0, 200).getContent();
    }

    long projectCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM project WHERE organization_id = ?", Long.class, ORG);
    }

    <T> T inTenant(java.util.function.Supplier<T> work) {
        return work.get();
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

    UUID workbook(String name, String sheet, String[]... rows) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_ingest_document (id, organization_id, package_id, source_key, output_prefix, sha256, "
                + "file_name, file_type, size_bytes, status, created_at, updated_at) VALUES (?, ?, ?, 'k', ?, ?, ?, 'XLSX', 1, "
                + "'PARSED', now(), now())", id, ORG, packageId, prefix(id), "c".repeat(64), name);
        objects.put(prefix(id) + "meta.json", ("{\"fileType\": \"XLSX\", \"sheets\": [{\"index\": 0, \"name\": \"" + sheet
                + "\"}]}").getBytes(StandardCharsets.UTF_8));
        StringBuilder tables = new StringBuilder();
        for (int r = 0; r < rows.length; r++) {
            tables.append("{\"sheet\": \"").append(sheet).append("\", \"sheetIndex\": 0, \"page\": null, \"row\": ")
                    .append(r + 1).append(", \"hidden\": false, \"cells\": [");
            for (int c = 0; c < rows[r].length; c++) {
                String ref = (char) ('A' + c) + Integer.toString(r + 1);
                tables.append(c == 0 ? "" : ",").append("{\"ref\": \"").append(ref).append("\", \"col\": ").append(c + 1)
                        .append(", \"value\": \"").append(rows[r][c]).append("\", \"type\": \"TEXT\", \"text\": \"")
                        .append(rows[r][c]).append("\", \"formula\": false, \"noCachedValue\": false, \"mergeOrigin\": null}");
            }
            tables.append("]}\n");
        }
        objects.put(prefix(id) + "tables.jsonl", tables.toString().getBytes(StandardCharsets.UTF_8));
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

    Long project(String name, LocalDateTime start) {
        Project project = new Project();
        project.setProjectName(name);
        project.setProjectAddress("Northfield");
        project.setStartDate(start);
        project.setOrganization(organizations.findById(ORG).orElseThrow());
        return projects.save(project).getId();
    }

    Long contract(Long project, String name) {
        SubContract c = new SubContract();
        Organization org = organizations.findById(ORG).orElseThrow();
        c.setOrganization(org);
        c.setProjectId(project);
        c.setContractName(name);
        c.setContractorName("Sample Builders");
        c.setContractValue(BigDecimal.valueOf(2500000));
        return subContracts.save(c).getId();
    }
}
