package org.tornotron.echno_backend.modules.documentingestion.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import org.tornotron.echno_backend.modules.documentingestion.apply.BoqItemApplier;
import org.tornotron.echno_backend.modules.documentingestion.apply.ChangeApplier;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractionMethod;
import org.tornotron.echno_backend.modules.documentingestion.dto.ApplyResultDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.CandidateDecision;
import org.tornotron.echno_backend.modules.documentingestion.dto.CandidateDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.ConflictChoiceRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.ProvenanceDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.RevisionDiffDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.RevisionRequest;
import org.tornotron.echno_backend.modules.documentingestion.extraction.CandidateWriter;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ClassificationWriter;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ProposedCandidate;
import org.tornotron.echno_backend.modules.documentingestion.extraction.classify.ClassificationExtractor;
import org.tornotron.echno_backend.modules.documentingestion.extraction.corrigendum.ChangeLinker;
import org.tornotron.echno_backend.modules.documentingestion.extraction.corrigendum.ChangeMatcher;
import org.tornotron.echno_backend.modules.documentingestion.extraction.corrigendum.CorrigendumExtractor;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkReader;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkRecorder;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractorRegistry;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.IngestLlmProperties;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.LlmBudget;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.LlmGate;
import org.tornotron.echno_backend.modules.documentingestion.parsing.ParsedOutputReader;
import org.tornotron.echno_backend.modules.documentingestion.review.ConflictService;
import org.tornotron.echno_backend.modules.documentingestion.review.ProvenanceService;
import org.tornotron.echno_backend.modules.documentingestion.review.ReviewService;
import org.tornotron.echno_backend.modules.documentingestion.service.ClassificationService;
import org.tornotron.echno_backend.modules.documentingestion.service.IngestSettingsService;
import org.tornotron.echno_backend.modules.documentingestion.service.RevisionService;
import org.tornotron.echno_backend.modules.workprogress.api.ContractBoqPort;
import org.tornotron.echno_backend.modules.workprogress.billing.service.BillingSupport;
import org.tornotron.echno_backend.modules.workprogress.billing.service.ContractBillingService;
import org.tornotron.echno_backend.modules.workprogress.billing.service.ContractBoqPortAdapter;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.subcontract.SubContract;
import org.tornotron.echno_backend.subcontract.SubContractRepository;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Step 14 of the build plan, on the real migration with the LLM endpoint stubbed: the done-when lines of
 * design section 4.8. A corrigendum PDF gives one change, 850 to 920, +70, 686,000, matched to the BOQ line
 * already on the contract; a statement matching no item stays on the unmatched list; a line edited by hand
 * since it was applied is never overwritten silently, and a person chooses among the three values. A revised
 * BOQ file is compared row by row with no LLM.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@TestPropertySource(properties = {
        "echno.modules.document-ingestion.enabled=true",
        "echno.modules.document-ingestion.allowed-org-ids=" + CorrigendumIngestionIT.ORG,
        "echno.modules.document-ingestion.llm.initial-delay-millis=86400000"
})
@Import({ExtractJobDispatcher.class, ExtractJobWorker.class, ExtractJobQueue.class, ChunkReader.class,
        ChunkRecorder.class, LlmGate.class, LlmBudget.class, IngestLlmProperties.class, LlmEndpointProperties.class,
        ExtractorRegistry.class, ClassificationExtractor.class, ClassificationWriter.class, CandidateWriter.class,
        CorrigendumExtractor.class, ChangeLinker.class, ParsedOutputReader.class, IngestSettingsService.class,
        ClassificationService.class, ReviewService.class, ApplyService.class, ApplyGuard.class, ChangeApplier.class,
        RevisionService.class, ConflictService.class, ProvenanceService.class, ContractBoqPortAdapter.class,
        ContractBillingService.class, BillingSupport.class,
        TenantScopedJobRunner.class, TransactionalWorkRunner.class, TransactionRetryTemplate.class,
        SimpleMeterRegistry.class, UserContextService.class, TenantEntityHelper.class,
        AopAutoConfiguration.class, HibernateFilterConfig.class, TenantIsolationListenerRegistrar.class,
        UnscopedAccessGuard.class})
class CorrigendumIngestionIT extends AbstractIntegrationTest {

    static final long ORG = 913_014_101L;
    static final Executor DIRECT = Runnable::run;

    static final String ANSWER = """
            {"changes": [
              {"kind": "BOQ_ITEM", "target": "1.03", "field": "quantity", "old_value": "850", "new_value": "920", "page": 1,
               "quote": "Item 1.03 RCC M25: quantity revised from 850 cum to 920 cum"},
              {"kind": "BOQ_ITEM", "target": "4.12", "field": "rate", "old_value": null, "new_value": "640", "page": 1,
               "quote": "Item 4.12 Plastering: rate to be read as Rs. 640 per sqm"}
            ]}""";

    @Autowired
    ExtractJobDispatcher dispatcher;

    @Autowired
    ExtractJobQueue queue;

    @Autowired
    CorrigendumExtractor extractor;

    @Autowired
    ReviewService review;

    @Autowired
    ApplyService apply;

    @Autowired
    ConflictService conflicts;

    @Autowired
    RevisionService revisions;

    @Autowired
    ProvenanceService provenance;

    @Autowired
    CandidateWriter writer;

    @Autowired
    ContractBoqPort contractBoq;

    @Autowired
    IngestLlmProperties properties;

    @Autowired
    TransactionRetryTemplate retry;

    @Autowired
    TenantEntityHelper tenantEntityHelper;

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
    ModuleRegistry modules;

    final Map<String, byte[]> objects = new HashMap<>();
    UUID packageId;
    UUID tenderBoq;
    Long contractId;

    @BeforeEach
    void aPackageWhoseBoqLineIsOnTheContract() {
        jdbc.update("INSERT INTO organization (id, organization_name, organization_address, organization_email, "
                + "organization_phone) VALUES (?, ?, 'address', ?, '0000000000')", ORG, "Corrigendum " + UUID.randomUUID(),
                UUID.randomUUID() + "@example.test");
        TenantContext.setCurrentOrgId(ORG);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("pm", "n/a", "ROLE_USER"));
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);
        when(modules.isEnabledForOrg(eq(ContractBoqPort.MODULE_ID), anyLong())).thenReturn(true);
        properties.setBackoff(List.of(Duration.ZERO, Duration.ZERO, Duration.ZERO));
        when(client.isAvailable()).thenReturn(true);
        when(client.complete(any())).thenAnswer(inv -> ok(ANSWER));
        when(storage.readObject(any(), anyLong())).thenAnswer(inv -> Optional.ofNullable(objects.get(inv.<String>getArgument(0)))
                .map(b -> new FileStorageService.StoredObject(inv.getArgument(0), "application/json", b)));
        when(storage.objectExists(argThat(k -> k != null && k.endsWith(DocumentIngestionStorageLayout.META_FILE))))
                .thenReturn(true);

        Project project = new Project();
        project.setProjectName("Riverside annexe");
        project.setOrganization(organizations.findById(ORG).orElseThrow());
        Long projectId = projects.save(project).getId();
        SubContract c = new SubContract();
        c.setOrganization(organizations.findById(ORG).orElseThrow());
        c.setProjectId(projectId);
        c.setContractName("Civil works");
        c.setContractorName("Sample Builders");
        c.setContractValue(BigDecimal.valueOf(25_000_000));
        contractId = subContracts.save(c).getId();
        packageId = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_ingest_package (id, organization_id, name, project_id, target_sub_contract_id, stage, "
                + "status, created_at, updated_at) VALUES (?, ?, 'Riverside', ?, ?, 'AWARDED', 'OPEN', now(), now())",
                packageId, ORG, projectId, contractId);
        tenderBoq = document("BOQ.xlsx", "XLSX");
    }

    @AfterEach
    void removeRows() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        for (String table : List.of("doc_ingest_provenance", "doc_ingest_llm_call", "doc_ingest_source_ref",
                "doc_ingest_candidate", "doc_ingest_extract_job", "doc_ingest_segment", "doc_ingest_document",
                "doc_ingest_package", "contract_boq_item", "sub_contract", "project")) {
            jdbc.update("DELETE FROM " + table + " WHERE organization_id = ?", ORG);
        }
        jdbc.update("DELETE FROM organization WHERE id = ?", ORG);
    }

    @Test
    void theStubsExampleGivesOneChangeOnTheLiveLineAndTheUnmatchedStatementIsKept() {
        UUID itemId = appliedBoqLine("1.03", "850", "9800");
        readCorrigendum();

        Map<String, CandidateDto> changes = changes();
        assertThat(changes).hasSize(2);
        CandidateDto rcc = changes.get("BOQ_ITEM:1.03:quantity");
        assertThat(rcc.supersedesCandidateId()).isNotNull();
        assertThat(rcc.payload()).containsEntry(ChangeMatcher.OLD_VALUE, "850").containsEntry(ChangeMatcher.NEW_VALUE, "920")
                .containsEntry(ChangeMatcher.QUANTITY_VARIANCE, "70").containsEntry(ChangeMatcher.MONEY_IMPACT, "686000");
        assertThat(rcc.sources()).allSatisfy(s -> assertThat(s.quoteVerified()).isTrue());
        CandidateDto unmatched = changes.get("BOQ_ITEM:4.12:rate");
        assertThat(unmatched.supersedesCandidateId()).isNull();
        assertThat(unmatched.flags()).extracting(f -> f.code()).contains(ChangeMatcher.UNMATCHED);

        accept(rcc);
        ApplyResultDto result = apply.apply(packageId, CandidateKind.CHANGE);

        assertThat(result.applied()).isEqualTo(1);
        assertThat(quantity(itemId)).isEqualByComparingTo("920");
        assertThat(provenance.forTarget(BoqItemApplier.TARGET_TYPE, itemId.toString()))
                .filteredOn(p -> p.candidateId().equals(rcc.id())).singleElement()
                .satisfies(p -> {
                    assertThat(p.targetField()).isEqualTo("contractQuantity");
                    assertThat(p.valueAccepted()).isEqualTo("920");
                    assertThat(p.source().quote()).contains("920 cum");
                });
        assertThat(status(rcc.supersedesCandidateId())).isEqualTo("SUPERSEDED");
        assertThat(status(unmatched.id())).as("never dropped").isEqualTo("PROPOSED");
    }

    @Test
    void aLineEditedByHandSinceItWasAppliedIsNeverOverwrittenAndAPersonChooses() {
        UUID itemId = appliedBoqLine("1.03", "850", "9800");
        readCorrigendum();
        CandidateDto rcc = changes().get("BOQ_ITEM:1.03:quantity");
        accept(rcc);
        jdbc.update("UPDATE contract_boq_item SET contract_quantity = 900, amount = 900 * rate WHERE id = ?", itemId);

        ApplyResultDto refused = apply.apply(packageId, CandidateKind.CHANGE);

        assertThat(refused.failed()).isEqualTo(1);
        assertThat(quantity(itemId)).isEqualByComparingTo("900");
        String error = jdbc.queryForObject("SELECT apply_error FROM doc_ingest_candidate WHERE id = ?", String.class, rcc.id());
        assertThat(error).contains("900").contains("850").contains("920");

        conflicts.choose(rcc.id(), new ConflictChoiceRequest(ConflictChoiceRequest.Choice.APPLY_CHANGE));
        assertThat(apply.apply(packageId, CandidateKind.CHANGE).applied()).isEqualTo(1);
        assertThat(quantity(itemId)).isEqualByComparingTo("920");
    }

    @Test
    void keepingTheRecordRejectsTheChangeAndPutsTheEarlierValueBack() {
        UUID itemId = appliedBoqLine("1.03", "850", "9800");
        readCorrigendum();
        CandidateDto rcc = changes().get("BOQ_ITEM:1.03:quantity");
        accept(rcc);
        jdbc.update("UPDATE contract_boq_item SET contract_quantity = 900, amount = 900 * rate WHERE id = ?", itemId);
        apply.apply(packageId, CandidateKind.CHANGE);

        conflicts.choose(rcc.id(), new ConflictChoiceRequest(ConflictChoiceRequest.Choice.KEEP_RECORD));

        assertThat(status(rcc.id())).isEqualTo("REJECTED");
        assertThat(status(rcc.supersedesCandidateId())).isEqualTo("APPLIED");
        assertThat(apply.apply(packageId, CandidateKind.CHANGE).applied()).isZero();
        assertThat(quantity(itemId)).isEqualByComparingTo("900");
    }

    @Test
    void aChangeToAValueNotYetAppliedProposesTheRevisedValueForReview() {
        UUID prior = boqRow(tenderBoq, "1.03", "850", "9800", 14);
        readCorrigendum();
        CandidateDto rcc = changes().get("BOQ_ITEM:1.03:quantity");
        assertThat(rcc.supersedesCandidateId()).isEqualTo(prior);
        accept(rcc);

        assertThat(apply.apply(packageId, CandidateKind.CHANGE).applied()).isEqualTo(1);

        List<CandidateDto> revised = review.list(packageId, CandidateKind.BOQ_ITEM, CandidateStatus.PROPOSED, null, 0, 50)
                .getContent();
        assertThat(revised).singleElement().satisfies(r -> {
            assertThat(r.supersedesCandidateId()).isEqualTo(prior);
            assertThat(r.payload()).containsEntry("quantity", "920").containsEntry("rate", "9800");
            assertThat(r.sources()).anySatisfy(s -> {
                assertThat(s.field()).isEqualTo("quantity");
                assertThat(s.quote()).contains("920 cum");
            });
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract_boq_item WHERE organization_id = ?", Long.class, ORG))
                .isZero();
    }

    @Test
    void aRevisedBoqIsComparedRowByRowWithNoLlm() {
        boqRow(tenderBoq, "1.03", "850", "9800", 14);
        boqRow(tenderBoq, "1.04", "100", "500", 15);
        boqRow(tenderBoq, "1.05", "10", "50", 16);
        boqRow(tenderBoq, "PC-7", "3", "200", 17);
        UUID revisedFile = document("BOQ rev 1.xlsx", "XLSX");
        boqRow(revisedFile, "1.03", "920", "9800", 14);
        boqRow(revisedFile, "1.04", "100", "500", 15);
        boqRow(revisedFile, "1.06", "7", "70", 16);
        boqRow(revisedFile, "pc-7", "3", "200", 17);

        revisions.markRevision(revisedFile, new RevisionRequest(tenderBoq, "R1"));
        RevisionDiffDto diff = revisions.diff(revisedFile);

        assertThat(diff).as("an item code differing only in case is the same item").isEqualTo(new RevisionDiffDto(3, 3, 1, 1));
        Map<String, CandidateDto> changes = changes();
        CandidateDto rcc = changes.get("BOQ_ITEM:1.03:quantity");
        assertThat(rcc.payload()).containsEntry(ChangeMatcher.MONEY_IMPACT, "686000")
                .containsEntry(ChangeMatcher.ORIGIN, ChangeMatcher.ORIGIN_REVISED_FILE);
        assertThat(rcc.sources()).allSatisfy(s -> assertThat(s.documentId()).isEqualTo(revisedFile));
        assertThat(rcc.sources()).anySatisfy(s -> {
            assertThat(s.field()).isEqualTo(ChangeMatcher.NEW_VALUE);
            assertThat(s.cell()).isEqualTo("D14");
        }).anySatisfy(s -> {
            assertThat(s.field()).isEqualTo(ChangeMatcher.TARGET);
            assertThat(s.cell()).isEqualTo("A14");
        });
        assertThat(changes.get("BOQ_ITEM:1.06:").flags()).extracting(f -> f.code()).contains(ChangeMatcher.ITEM_ADDED);
        CandidateDto gone = changes.get("BOQ_ITEM:1.05:");
        assertThat(gone.flags()).extracting(f -> f.code()).contains(ChangeMatcher.ITEM_REMOVED);
        assertThat(gone.payload()).as("a removal is inferred, not read").doesNotContainKey(ChangeMatcher.NEW_VALUE);
        assertThat(gone.sources()).extracting(s -> s.field()).containsExactly(ChangeMatcher.TARGET);
        assertThat(revisions.diff(revisedFile).changesProposed()).as("run again, it adds nothing").isZero();

        accept(rcc);
        assertThat(status(rcc.supersedesCandidateId())).isEqualTo("SUPERSEDED");
        assertThat(revisions.diff(revisedFile).changesProposed()).as("accepting a change leaves the comparison as it was")
                .isZero();
    }

    @Test
    void theEarlierFileMustBeReadAndCannotBeChangedUnderChangesInReview() {
        UUID revisedFile = document("BOQ rev 1.xlsx", "XLSX");
        boqRow(revisedFile, "1.03", "920", "9800", 14);
        UUID unread = document("BOQ draft.xlsx", "XLSX");

        revisions.markRevision(revisedFile, new RevisionRequest(unread, "R1"));
        assertThatThrownBy(() -> revisions.diff(revisedFile)).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("BOQ draft.xlsx");
        assertThat(changes()).isEmpty();

        boqRow(tenderBoq, "1.03", "850", "9800", 14);
        revisions.markRevision(revisedFile, new RevisionRequest(tenderBoq, "R1"));
        assertThat(revisions.diff(revisedFile).changesProposed()).isEqualTo(1);
        UUID other = document("BOQ other.xlsx", "XLSX");
        boqRow(other, "1.03", "900", "9800", 14);

        assertThatThrownBy(() -> revisions.markRevision(revisedFile, new RevisionRequest(other, "R1")))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("still in review");

        review.decide(packageId, List.of(new CandidateDecision(changes().get("BOQ_ITEM:1.03:quantity").id(),
                CandidateDecision.Action.REJECT, null, List.of())));
        revisions.markRevision(revisedFile, new RevisionRequest(other, "R1"));
        assertThat(revisions.diff(revisedFile).changesProposed()).as("compared afresh against the new file").isEqualTo(1);
    }

    @Test
    void aLaterCorrigendumChangesTheValueTheEarlierOneWroteToTheLine() {
        UUID itemId = appliedBoqLine("1.03", "850", "9800");
        readCorrigendum();
        CandidateDto first = changesOf(null).get("BOQ_ITEM:1.03:quantity");
        accept(first);
        apply.apply(packageId, CandidateKind.CHANGE);
        assertThat(quantity(itemId)).isEqualByComparingTo("920");

        UUID second = readCorrigendum("Corrigendum 2.pdf", answer("1.03", "quantity", "920", "950 cum",
                "Item 1.03 RCC M25: quantity revised from 920 cum to 950 cum"),
                "Item 1.03 RCC M25: quantity revised from 920 cum to 950 cum");
        CandidateDto next = changesOf(second).get("BOQ_ITEM:1.03:quantity");
        assertThat(next.supersedesCandidateId()).as("the line's own row, though superseded").isEqualTo(first.supersedesCandidateId());
        assertThat(next.payload()).containsEntry(ChangeMatcher.PRIOR_VALUE, "920")
                .containsEntry(ChangeMatcher.QUANTITY_VARIANCE, "30").containsEntry(ChangeMatcher.MONEY_IMPACT, "294000");
        assertThat(next.flags()).isEmpty();
        accept(next);
        assertThat(apply.apply(packageId, CandidateKind.CHANGE).applied()).isEqualTo(1);
        assertThat(quantity(itemId)).as("the unit is not part of the number").isEqualByComparingTo("950");

        UUID third = readCorrigendum("Corrigendum 3.pdf", answer("1.03", "quantity", "950", "1000",
                "Item 1.03 RCC M25: quantity revised from 950 cum to 1000 cum"),
                "Item 1.03 RCC M25: quantity revised from 950 cum to 1000 cum");
        CandidateDto last = changesOf(third).get("BOQ_ITEM:1.03:quantity");
        accept(last);
        assertThat(apply.apply(packageId, CandidateKind.CHANGE).applied())
                .as("950 cum applied and 950 on the line are the same value, so no hand edit is seen").isEqualTo(1);
        assertThat(quantity(itemId)).isEqualByComparingTo("1000");
    }

    @Test
    void aValueOnlyPartlyANumberIsNotApplied() {
        UUID itemId = appliedBoqLine("1.03", "850", "9800");
        UUID doc = readCorrigendum("Corrigendum 1.pdf", answer("1.03", "quantity", "850", "1/2",
                "Item 1.03 RCC M25: quantity revised to 1/2 of 850 cum"),
                "Item 1.03 RCC M25: quantity revised to 1/2 of 850 cum");
        accept(changesOf(doc).get("BOQ_ITEM:1.03:quantity"));

        assertThat(apply.apply(packageId, CandidateKind.CHANGE).failed()).isEqualTo(1);
        assertThat(quantity(itemId)).isEqualByComparingTo("850");
    }

    @Test
    void aChangeToTheAmountOfALiveLineIsRefusedForTheQuantityOrRate() {
        UUID itemId = appliedBoqLine("1.03", "850", "9800");
        UUID doc = readCorrigendum("Corrigendum 1.pdf", answer("1.03", "amount", null, "9016000",
                "Item 1.03 RCC M25: amount to be read as 9016000"), "Item 1.03 RCC M25: amount to be read as 9016000");
        CandidateDto amount = changesOf(doc).get("BOQ_ITEM:1.03:amount");
        accept(amount);

        assertThat(apply.apply(packageId, CandidateKind.CHANGE).failed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT apply_error FROM doc_ingest_candidate WHERE id = ?", String.class,
                amount.id())).contains("quantity or the rate");
        assertThat(quantity(itemId)).isEqualByComparingTo("850");
    }

    @Test
    void twoChangesToARowNotYetAppliedGiveOneRevisedRowHoldingBoth() {
        UUID prior = boqRow(tenderBoq, "1.03", "850", "9800", 14);
        String quote = "Item 1.03 RCC M25: quantity revised to 920 cum and rate to 10000";
        UUID doc = readCorrigendum("Corrigendum 1.pdf", "{\"changes\": [" + change("1.03", "quantity", "850", "920", quote)
                + ", " + change("1.03", "rate", "9800", "10000", quote) + "]}", quote);
        Map<String, CandidateDto> changes = changesOf(doc);
        accept(changes.get("BOQ_ITEM:1.03:quantity"));
        accept(changes.get("BOQ_ITEM:1.03:rate"));

        assertThat(apply.apply(packageId, CandidateKind.CHANGE).applied()).isEqualTo(2);

        List<CandidateDto> revised = review.list(packageId, CandidateKind.BOQ_ITEM, CandidateStatus.PROPOSED, null, 0, 50)
                .getContent();
        assertThat(revised).singleElement().satisfies(r -> {
            assertThat(r.payload()).containsEntry("quantity", "920").containsEntry("rate", "10000");
            assertThat(r.documentId()).isEqualTo(doc);
            assertThat(r.segmentId()).as("the corrigendum's segment, with its document")
                    .isEqualTo(changes.get("BOQ_ITEM:1.03:rate").segmentId());
        });
        assertThat(status(prior)).isEqualTo("SUPERSEDED");
    }

    @Test
    void keepingTheRecordLeavesTheRowSupersededWhileAnotherChangeToItStands() {
        UUID itemId = appliedBoqLine("1.03", "850", "9800");
        String quote = "Item 1.03 RCC M25: quantity revised to 920 cum and rate to 10000";
        UUID doc = readCorrigendum("Corrigendum 1.pdf", "{\"changes\": [" + change("1.03", "quantity", "850", "920", quote)
                + ", " + change("1.03", "rate", "9800", "10000", quote) + "]}", quote);
        Map<String, CandidateDto> changes = changesOf(doc);
        accept(changes.get("BOQ_ITEM:1.03:quantity"));
        accept(changes.get("BOQ_ITEM:1.03:rate"));
        jdbc.update("UPDATE contract_boq_item SET rate = 9900, amount = contract_quantity * 9900 WHERE id = ?", itemId);

        ApplyResultDto result = apply.apply(packageId, CandidateKind.CHANGE);
        assertThat(result.applied()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        CandidateDto rate = changes.get("BOQ_ITEM:1.03:rate");
        conflicts.choose(rate.id(), new ConflictChoiceRequest(ConflictChoiceRequest.Choice.KEEP_RECORD));

        assertThat(status(rate.supersedesCandidateId())).as("the applied quantity change still stands over it")
                .isEqualTo("SUPERSEDED");
        assertThat(quantity(itemId)).isEqualByComparingTo("920");
    }

    @Test
    void aChoiceToWriteOverTheRecordHoldsOnlyForTheValueItWasMadeOn() {
        UUID itemId = appliedBoqLine("1.03", "850", "9800");
        readCorrigendum();
        CandidateDto rcc = changes().get("BOQ_ITEM:1.03:quantity");
        accept(rcc);
        jdbc.update("UPDATE contract_boq_item SET contract_quantity = 900, amount = 900 * rate WHERE id = ?", itemId);
        apply.apply(packageId, CandidateKind.CHANGE);
        conflicts.choose(rcc.id(), new ConflictChoiceRequest(ConflictChoiceRequest.Choice.APPLY_CHANGE));
        jdbc.update("UPDATE contract_boq_item SET contract_quantity = 910, amount = 910 * rate WHERE id = ?", itemId);

        assertThat(apply.apply(packageId, CandidateKind.CHANGE).failed()).isEqualTo(1);
        assertThat(quantity(itemId)).as("edited again after the choice, so not overwritten").isEqualByComparingTo("910");
        assertThat(jdbc.queryForObject("SELECT apply_error FROM doc_ingest_candidate WHERE id = ?", String.class, rcc.id()))
                .contains("910");

        conflicts.choose(rcc.id(), new ConflictChoiceRequest(ConflictChoiceRequest.Choice.APPLY_CHANGE));
        assertThat(apply.apply(packageId, CandidateKind.CHANGE).applied()).isEqualTo(1);
        assertThat(quantity(itemId)).isEqualByComparingTo("920");
    }

    @Test
    void aProjectFieldChangeWithNoFieldProposesTheRevisedValue() {
        UUID due = retry.execute("test.projectField", () -> writer.propose(new ProposedCandidate(packageId, tenderBoq, null,
                CandidateKind.PROJECT_FIELD, "bid_due_date", new LinkedHashMap<>(Map.of("value", "2026-12-01")), List.of(),
                ExtractionMethod.LLM, List.of(new ProposedCandidate.ProposedSource("value", tenderBoq, "d".repeat(64), 1,
                        null, null, null, null, null, "Last date for bids: 2026-12-01", true)), null)));
        String quote = "Bid due date extended from 2026-12-01 to 2026-12-15";
        UUID doc = readCorrigendum("Corrigendum 1.pdf", "{\"changes\": [{\"kind\": \"PROJECT_FIELD\", \"target\": "
                + "\"bid_due_date\", \"old_value\": \"2026-12-01\", \"new_value\": \"2026-12-15\", \"page\": 1, "
                + "\"quote\": \"" + quote + "\"}]}", quote);
        CandidateDto change = changesOf(doc).get("PROJECT_FIELD:bid_due_date:");
        assertThat(change.supersedesCandidateId()).isEqualTo(due);
        assertThat(change.payload()).containsEntry(ChangeMatcher.PRIOR_VALUE, "2026-12-01");
        assertThat(change.flags()).isEmpty();
        accept(change);

        assertThat(apply.apply(packageId, CandidateKind.CHANGE).applied()).isEqualTo(1);

        assertThat(review.list(packageId, CandidateKind.PROJECT_FIELD, CandidateStatus.PROPOSED, null, 0, 50).getContent())
                .singleElement().satisfies(r -> assertThat(r.payload()).containsEntry("value", "2026-12-15"));
    }

    // --------------------------------------------------------------------------------------------
    // Fixtures
    // --------------------------------------------------------------------------------------------

    void readCorrigendum() {
        readCorrigendum("Corrigendum 1.pdf", ANSWER, "Item 1.03 RCC M25: quantity revised from 850 cum to 920 cum",
                "Item 4.12 Plastering: rate to be read as Rs. 640 per sqm");
    }

    /** Reads a one-page corrigendum holding these lines, the model giving this answer. */
    UUID readCorrigendum(String name, String answer, String... lines) {
        when(client.complete(any())).thenAnswer(inv -> ok(answer));
        UUID doc = document(name, "PDF");
        String prefix = DocumentIngestionStorageLayout.outputPrefix(ORG, packageId, doc);
        objects.put(prefix + "meta.json", "{\"fileType\": \"PDF\", \"pageCount\": 1, \"warnings\": []}"
                .getBytes(StandardCharsets.UTF_8));
        StringBuilder page = new StringBuilder("{\"page\": 1, \"method\": \"TEXT\", \"lines\": ["
                + "{\"text\": \"CORRIGENDUM\", \"box\": [0.1, 0.1, 0.9, 0.14]}");
        for (int i = 0; i < lines.length; i++) {
            double top = 0.2 + 0.1 * i;
            page.append(", {\"text\": \"").append(lines[i]).append("\", \"box\": [0.1, ").append(top).append(", 0.9, ")
                    .append(top + 0.04).append("]}");
        }
        page.append("], \"status\": \"OK\", \"reason\": null, \"script\": \"LATIN\"}");
        objects.put(prefix + "pages.jsonl", page.toString().getBytes(StandardCharsets.UTF_8));
        UUID segment = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_ingest_segment (id, organization_id, document_id, doc_class, page_from, page_to, "
                + "status, confirmed_at, created_at, updated_at) VALUES (?, ?, ?, 'CORRIGENDUM', 1, 1, 'CONFIRMED', now(), "
                + "now(), now())", segment, ORG, doc);
        retry.execute("test.queue", () -> queue.queue(tenantEntityHelper.resolveCurrentOrganization(), packageId, doc,
                segment, extractor, null));
        for (int i = 0; i < 5; i++) {
            dispatcher.poll(DIRECT, DIRECT);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM doc_ingest_extract_job WHERE document_id = ?", String.class, doc))
                .isEqualTo("DONE");
        return doc;
    }

    static String change(String target, String field, String oldValue, String newValue, String quote) {
        return "{\"kind\": \"BOQ_ITEM\", \"target\": \"" + target + "\", \"field\": \"" + field + "\", \"old_value\": "
                + (oldValue == null ? "null" : "\"" + oldValue + "\"") + ", \"new_value\": \"" + newValue
                + "\", \"page\": 1, \"quote\": \"" + quote + "\"}";
    }

    static String answer(String target, String field, String oldValue, String newValue, String quote) {
        return "{\"changes\": [" + change(target, field, oldValue, newValue, quote) + "]}";
    }

    UUID document(String name, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_ingest_document (id, organization_id, package_id, source_key, output_prefix, sha256, "
                + "file_name, file_type, size_bytes, status, page_count, created_at, updated_at) VALUES (?, ?, ?, 'k', ?, ?, ?, "
                + "?, 1, 'PARSED', 1, now(), now())", id, ORG, packageId,
                DocumentIngestionStorageLayout.outputPrefix(ORG, packageId, id), "d".repeat(64), name, type);
        return id;
    }

    /** A BOQ row read from a workbook, with a source cell per field. */
    UUID boqRow(UUID document, String code, String quantity, String rate, int row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("itemCode", code);
        payload.put("description", "Item " + code);
        payload.put("unit", "cum");
        payload.put("quantity", quantity);
        payload.put("rate", rate);
        List<ProposedCandidate.ProposedSource> sources = new ArrayList<>();
        String[] cols = {"A", "B", "C", "D", "E"};
        String[] fields = {"itemCode", "description", "unit", "quantity", "rate"};
        for (int i = 0; i < fields.length; i++) {
            sources.add(new ProposedCandidate.ProposedSource(fields[i], document, "d".repeat(64), null, null, "BOQ", row,
                    cols[i] + row, null, payload.get(fields[i]).toString(), true));
        }
        return retry.execute("test.boqRow", () -> writer.propose(new ProposedCandidate(packageId, document, null,
                CandidateKind.BOQ_ITEM, "BOQ:" + row + ":" + document, payload, List.of(), ExtractionMethod.DETERMINISTIC,
                sources, null)));
    }

    /** A BOQ row already applied to the contract's BOQ. */
    UUID appliedBoqLine(String code, String quantity, String rate) {
        UUID candidate = boqRow(tenderBoq, code, quantity, rate, 14);
        UUID itemId = retry.execute("test.addItem", () -> contractBoq.addItem(contractId, new ContractBoqPort.BoqLine(code,
                "Item " + code, "cum", new BigDecimal(quantity), new BigDecimal(rate), null)));
        jdbc.update("UPDATE doc_ingest_candidate SET status = 'APPLIED', applied_target_type = ?, applied_target_id = ?, "
                + "applied_at = now(), reviewed_at = now() WHERE id = ?", BoqItemApplier.TARGET_TYPE, itemId.toString(),
                candidate);
        return itemId;
    }

    void accept(CandidateDto c) {
        review.decide(packageId, List.of(new CandidateDecision(c.id(), CandidateDecision.Action.ACCEPT, null,
                c.flags().stream().map(f -> f.code()).toList())));
    }

    Map<String, CandidateDto> changes() {
        return changesOf(null);
    }

    /** The changes read from one document, or from any when it is null, by natural key. */
    Map<String, CandidateDto> changesOf(UUID document) {
        Map<String, CandidateDto> out = new HashMap<>();
        review.list(packageId, CandidateKind.CHANGE, null, null, 0, 50).getContent().stream()
                .filter(c -> document == null || document.equals(c.documentId())).forEach(c -> out.put(c.naturalKey(), c));
        return out;
    }

    BigDecimal quantity(UUID itemId) {
        return jdbc.queryForObject("SELECT contract_quantity FROM contract_boq_item WHERE id = ?", BigDecimal.class, itemId);
    }

    String status(UUID candidateId) {
        return jdbc.queryForObject("SELECT status FROM doc_ingest_candidate WHERE id = ?", String.class, candidateId);
    }

    static ChatCompletionResult ok(String content) {
        return new ChatCompletionResult(ChatCompletionOutcome.OK, 200, content, "stop", 100, 20, 120, "test-model",
                Duration.ofMillis(7), null, "{}");
    }
}
