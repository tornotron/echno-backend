package org.tornotron.echno_backend.modules.documentingestion.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
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
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.dto.AttachmentOwner;
import org.tornotron.echno_backend.common.dto.PresignedUpload;
import org.tornotron.echno_backend.common.entity.Attachment;
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
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionStorageLayout;
import org.tornotron.echno_backend.modules.documentingestion.apply.ApplyGuard;
import org.tornotron.echno_backend.modules.documentingestion.apply.ApplyService;
import org.tornotron.echno_backend.modules.documentingestion.apply.QualificationRequirementApplier;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.CompanyProfile;
import org.tornotron.echno_backend.modules.documentingestion.domain.EvidenceSection;
import org.tornotron.echno_backend.modules.documentingestion.domain.MatrixStatus;
import org.tornotron.echno_backend.modules.documentingestion.dto.ApplyResultDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.CandidateDecision;
import org.tornotron.echno_backend.modules.documentingestion.dto.CandidateDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.CompanyEvidenceDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.CompanyProfileRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.ComplianceMatrixDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.EvidenceUploadDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.PresignEvidenceRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.ProvenanceDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.QualificationRequirementEntryDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.RegisterDocumentRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.RequirementStatusRequest;
import org.tornotron.echno_backend.modules.documentingestion.extraction.CandidateWriter;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ClassificationWriter;
import org.tornotron.echno_backend.modules.documentingestion.extraction.classify.ClassificationExtractor;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkReader;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkRecorder;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractorRegistry;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.IngestLlmProperties;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.LlmBudget;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.LlmGate;
import org.tornotron.echno_backend.modules.documentingestion.extraction.qualification.QualificationExtractor;
import org.tornotron.echno_backend.modules.documentingestion.parsing.ParsedOutputReader;
import org.tornotron.echno_backend.modules.documentingestion.review.ProvenanceService;
import org.tornotron.echno_backend.modules.documentingestion.review.ReviewService;
import org.tornotron.echno_backend.modules.documentingestion.service.ClassificationService;
import org.tornotron.echno_backend.modules.documentingestion.service.DocumentIngestionLimits;
import org.tornotron.echno_backend.modules.documentingestion.service.ExtractionService;
import org.tornotron.echno_backend.modules.documentingestion.service.IngestSettingsService;
import org.tornotron.echno_backend.modules.documentingestion.service.QualificationComparison;
import org.tornotron.echno_backend.modules.documentingestion.service.QualificationService;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Step 21 of the build plan, on the real migration with the LLM endpoint stubbed and the worker's outputs
 * served from a mocked object store: the done-when lines of design section 4.7. The stub's tender, read from
 * a notice inviting tenders, accepted and applied, set beside the stub's company profile gives its five-row
 * matrix with key personnel as a gap; a blank profile field gives UNKNOWN and never GAP; a package already
 * awarded is not read and shows no matrix.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@TestPropertySource(properties = {
        "echno.modules.document-ingestion.enabled=true",
        "echno.modules.document-ingestion.allowed-org-ids=" + QualificationIngestionIT.ORG,
        "echno.modules.document-ingestion.llm.initial-delay-millis=86400000"
})
@Import({ExtractJobDispatcher.class, ExtractJobWorker.class, ExtractJobQueue.class, ChunkReader.class,
        ChunkRecorder.class, LlmGate.class, LlmBudget.class, IngestLlmProperties.class, LlmEndpointProperties.class,
        ExtractorRegistry.class, ClassificationExtractor.class, ClassificationWriter.class, CandidateWriter.class,
        QualificationExtractor.class, ParsedOutputReader.class, IngestSettingsService.class, ClassificationService.class,
        ExtractionService.class, ReviewService.class, ApplyService.class, ApplyGuard.class,
        QualificationRequirementApplier.class, QualificationService.class, DocumentIngestionLimits.class,
        ProvenanceService.class, TenantScopedJobRunner.class, TransactionalWorkRunner.class,
        TransactionRetryTemplate.class, SimpleMeterRegistry.class, UserContextService.class, TenantEntityHelper.class,
        AopAutoConfiguration.class, HibernateFilterConfig.class, TenantIsolationListenerRegistrar.class,
        UnscopedAccessGuard.class})
class QualificationIngestionIT extends AbstractIntegrationTest {

    static final long ORG = 913_021_101L;
    static final Executor DIRECT = Runnable::run;
    static final BigDecimal CRORE = BigDecimal.valueOf(10_000_000L);

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
    QualificationService qualification;

    @Autowired
    IngestLlmProperties properties;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    ChatCompletionClient client;

    @MockitoBean
    FileStorageService storage;

    @MockitoBean
    AttachmentService attachments;

    @MockitoBean(name = "orgSecurity")
    OrganizationSecurityService orgSecurity;

    final Map<String, byte[]> objects = new HashMap<>();
    final List<ChatCompletionRequest> sent = new ArrayList<>();
    UUID packageId;

    @BeforeEach
    void anOrganizationAndAnEmptyPackage() {
        jdbc.update("INSERT INTO organization (id, organization_name, organization_address, organization_email, "
                + "organization_phone) VALUES (?, ?, 'address', ?, '0000000000')", ORG, "Qualification " + UUID.randomUUID(),
                UUID.randomUUID() + "@example.test");
        packageId = pkg("TENDER");
        properties.setBackoff(List.of(Duration.ZERO, Duration.ZERO, Duration.ZERO));
        when(client.isAvailable()).thenReturn(true);
        when(client.complete(any())).thenAnswer(inv -> {
            ChatCompletionRequest req = inv.getArgument(0);
            if (!req.systemPrompt().equals(QualificationExtractor.PROMPT.system())) {
                return ok("{\"pages\": []}");
            }
            sent.add(req);
            return ok(NIT_ANSWER);
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
                "doc_ingest_qualification_requirement", "doc_ingest_company_evidence", "doc_ingest_company_profile",
                "doc_ingest_candidate", "doc_ingest_extract_job", "doc_ingest_segment", "doc_ingest_document",
                "doc_ingest_package")) {
            jdbc.update("DELETE FROM " + table + " WHERE organization_id = ?", ORG);
        }
        jdbc.update("DELETE FROM organization WHERE id = ?", ORG);
    }

    /** The stub's tender, as a fictional notice inviting tenders states it. */
    static final String NIT_ANSWER = """
            {"requirements": [
              {"requirement": "turnover", "value": "Rs. 100 Cr", "page": 1, "quote": "3.1 The bidder shall have a minimum annual turnover of Rs. 100 Cr."},
              {"requirement": "similar_projects", "value": "3", "min_value": "Rs. 30 Cr", "page": 1, "quote": "3.2 The bidder shall have completed 3 similar projects, each of value not less than Rs. 30 Cr."},
              {"requirement": "experience", "value": "7 years", "page": 1, "quote": "3.3 The bidder shall have at least 7 years of experience in building works."},
              {"requirement": "equipment", "value": "one batching plant, two tower cranes and three transit mixers", "page": 2, "quote": "3.4 Required equipment: one batching plant, two tower cranes and three transit mixers."},
              {"requirement": "personnel", "value": "5 key personnel", "page": 2, "quote": "3.5 Required personnel: 5 key personnel, including a project manager and a safety officer."}
            ]}""";

    @Test
    void theStubsExampleGivesItsFiveRowMatrixWithKeyPersonnelAsAGap() {
        UUID nit = nitPdf(packageId);
        classifyAndRead(nit);

        assertThat(sent).as("one call for the notice").hasSize(1);
        List<CandidateDto> proposed = review.list(packageId, CandidateKind.QUALIFICATION_REQUIREMENT,
                CandidateStatus.PROPOSED, null, 0, 50).getContent();
        assertThat(proposed).hasSize(5).allSatisfy(c -> assertThat(c.flags()).isEmpty());
        assertThat(count("doc_ingest_qualification_requirement")).as("nothing in the register before apply").isZero();

        review.decide(packageId, proposed.stream()
                .map(c -> new CandidateDecision(c.id(), CandidateDecision.Action.ACCEPT, null, null)).toList());
        ApplyResultDto result = apply.apply(packageId, CandidateKind.QUALIFICATION_REQUIREMENT);
        assertThat(result.applied()).isEqualTo(5);
        assertThat(result.failed()).isZero();

        List<QualificationRequirementEntryDto> requirements = qualification.requirements(packageId);
        assertThat(requirements).extracting(QualificationRequirementEntryDto::requirement)
                .containsExactly("TURNOVER", "SIMILAR_PROJECTS", "EXPERIENCE", "EQUIPMENT", "PERSONNEL");
        QualificationRequirementEntryDto similar = requirements.get(1);
        assertThat(similar.value()).isEqualTo("3");
        assertThat(similar.minValue()).isEqualTo("Rs. 30 Cr");
        assertThat(similar.sourceClass()).isEqualTo("NIT");
        assertThat(provenance.forTarget(QualificationRequirementApplier.TARGET, similar.id().toString()))
                .extracting(ProvenanceDto::targetField).containsExactlyInAnyOrder("value", "minValue");
        assertThat(provenance.forTarget(QualificationRequirementApplier.TARGET, similar.id().toString()))
                .allSatisfy(p -> assertThat(p.source()).isNotNull());

        qualification.saveProfile(stubProfile());
        CompanyEvidenceDto certificate = evidence(EvidenceSection.TURNOVER, "CA certificate 2025-26");
        qualification.setStatus(requirements.get(3).id(), new RequirementStatusRequest(MatrixStatus.MATCH, null));
        qualification.setStatus(requirements.get(4).id(),
                new RequirementStatusRequest(MatrixStatus.GAP, "Four of the five posts are on the staff."));

        ComplianceMatrixDto matrix = qualification.matrix(packageId);

        assertThat(matrix.shown()).isTrue();
        assertThat(matrix.notice()).contains("does not recommend whether to bid");
        assertThat(matrix.rows()).extracting(ComplianceMatrixDto.ComplianceMatrixRow::label)
                .containsExactly("Turnover", "Similar projects", "Experience", "Equipment", "Key personnel");
        assertThat(matrix.rows()).extracting(ComplianceMatrixDto.ComplianceMatrixRow::status).containsExactly(MatrixStatus.MATCH,
                MatrixStatus.MATCH, MatrixStatus.MATCH, MatrixStatus.MATCH, MatrixStatus.GAP);
        ComplianceMatrixDto.ComplianceMatrixRow turnover = matrix.rows().get(0);
        assertThat(turnover.tenderValue()).isEqualTo("Rs. 100 Cr");
        assertThat(turnover.companyValue()).isEqualTo("₹132 Cr (2025-26)");
        assertThat(turnover.basis()).isEqualTo(QualificationComparison.Basis.COMPARED);
        assertThat(turnover.evidence()).extracting(ComplianceMatrixDto.ComplianceMatrixEvidence::id).containsExactly(certificate.id());
        assertThat(matrix.rows().get(1).tenderValue()).isEqualTo("3, each at least Rs. 30 Cr");
        assertThat(matrix.rows().get(1).companyValue()).isEqualTo("4 qualifying of 4 listed, largest ₹42 Cr");
        assertThat(matrix.rows().get(2).companyValue()).isEqualTo("11 years");
        ComplianceMatrixDto.ComplianceMatrixRow personnel = matrix.rows().get(4);
        assertThat(personnel.basis()).isEqualTo(QualificationComparison.Basis.SET_BY_PERSON);
        assertThat(personnel.companyValue()).startsWith("4 people listed");
        assertThat(personnel.statusSetAt()).isNotNull();
        assertThat(personnel.statusNote()).isEqualTo("Four of the five posts are on the staff.");
        assertThat(matrix.rows().get(1).evidence()).as("evidence shows only beside its own section").isEmpty();
    }

    @Test
    void aBlankProfileFieldGivesUnknownAndNeverGap() {
        UUID nit = nitPdf(packageId);
        classifyAndRead(nit);
        acceptAndApplyAll();
        List<QualificationRequirementEntryDto> requirements = qualification.requirements(packageId);
        qualification.setStatus(requirements.get(4).id(), new RequirementStatusRequest(MatrixStatus.GAP, null));

        ComplianceMatrixDto none = qualification.matrix(packageId);
        assertThat(none.rows()).hasSize(5).allSatisfy(r -> {
            assertThat(r.status()).as(r.label()).isEqualTo(MatrixStatus.UNKNOWN);
            assertThat(r.companyValue()).as(r.label()).isNull();
        });

        // Turnover below the requirement is a gap; every other field is left blank.
        qualification.saveProfile(new CompanyProfileRequest(null,
                List.of(new CompanyProfile.TurnoverYear("2025-26", CRORE.multiply(BigDecimal.valueOf(60)))),
                List.of(), List.of(), List.of()));
        ComplianceMatrixDto partial = qualification.matrix(packageId);
        assertThat(partial.rows()).extracting(ComplianceMatrixDto.ComplianceMatrixRow::status).containsExactly(MatrixStatus.GAP,
                MatrixStatus.UNKNOWN, MatrixStatus.UNKNOWN, MatrixStatus.UNKNOWN, MatrixStatus.UNKNOWN);
        assertThat(partial.rows().subList(1, 5)).allSatisfy(r ->
                assertThat(r.basis()).as(r.label()).isEqualTo(QualificationComparison.Basis.PROFILE_BLANK));
        assertThat(partial.rows().get(4).reason()).isEqualTo("The company profile lists no personnel.");
    }

    @Test
    void aNumericRequirementTakesNoStatusByHand() {
        classifyAndRead(nitPdf(packageId));
        acceptAndApplyAll();
        UUID turnover = qualification.requirements(packageId).get(0).id();

        assertThatThrownBy(() -> qualification.setStatus(turnover, new RequirementStatusRequest(MatrixStatus.MATCH, null)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("from its figures");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM doc_ingest_qualification_requirement WHERE "
                + "organization_id = ? AND person_status IS NOT NULL", Long.class, ORG)).isZero();
    }

    @Test
    void aPackageAlreadyAwardedIsNotReadAndShowsNoMatrix() {
        UUID awarded = pkg("AWARDED");
        UUID nit = nitPdf(awarded);
        jdbc.update("INSERT INTO doc_ingest_extract_job (id, organization_id, package_id, document_id, extractor_key, "
                + "extractor_version, status, queued_at, created_at, updated_at) VALUES (gen_random_uuid(), ?, ?, ?, "
                + "'classify', '1', 'QUEUED', now(), now(), now())", ORG, awarded, nit);
        runAll();
        classification.confirmAll(awarded);
        runAll();

        assertThat(sent).as("qualification is not read once the tender is won").isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM doc_ingest_extract_job WHERE package_id = ? AND "
                + "extractor_key = ?", Long.class, awarded, QualificationExtractor.KEY)).isZero();
        ComplianceMatrixDto matrix = qualification.matrix(awarded);
        assertThat(matrix.shown()).isFalse();
        assertThat(matrix.rows()).isEmpty();
        assertThat(matrix.notice()).contains("AWARDED");
        assertThatThrownBy(() -> apply.apply(awarded, CandidateKind.QUALIFICATION_REQUIREMENT))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("AWARDED");
    }

    @Test
    void evidenceIsUploadedByPresignAndRegisterUnderAKeyComposedHere() {
        EvidenceUploadDto upload = qualification.presignEvidence(new PresignEvidenceRequest(EvidenceSection.SIMILAR_PROJECTS,
                "Completion certificate", "../completion.pdf", "application/pdf", 2048L));
        String key = upload.upload().key();
        assertThat(key).isEqualTo(DocumentIngestionStorageLayout.evidenceKey(ORG, upload.evidenceId()));
        assertThat(qualification.profile().evidence()).as("not shown before it is registered").isEmpty();

        assertThatThrownBy(() -> qualification.registerEvidence(upload.evidenceId(),
                new RegisterDocumentRequest("doc-ingest/1/company-profile/x/source")))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("not issued");
        assertThatThrownBy(() -> qualification.presignEvidence(new PresignEvidenceRequest(EvidenceSection.OTHER, null,
                "tool.exe", null, 10L))).isInstanceOf(InvalidRequestException.class);

        when(storage.objectExists(key)).thenReturn(true);
        CompanyEvidenceDto registered = qualification.registerEvidence(upload.evidenceId(), new RegisterDocumentRequest(key));
        assertThat(registered.uploaded()).isTrue();
        assertThat(registered.fileName()).isEqualTo("completion.pdf");
        assertThat(qualification.profile().evidence()).extracting(CompanyEvidenceDto::id)
                .containsExactly(upload.evidenceId());

        qualification.deleteEvidence(upload.evidenceId());
        assertThat(qualification.profile().evidence()).isEmpty();
    }

    // --------------------------------------------------------------------------------------------
    // Fixtures
    // --------------------------------------------------------------------------------------------

    /** The stub's company: 132 crore turnover, four similar projects up to 42 crore, 11 years, four people. */
    static CompanyProfileRequest stubProfile() {
        return new CompanyProfileRequest(11,
                List.of(new CompanyProfile.TurnoverYear("2025-26", CRORE.multiply(BigDecimal.valueOf(132)))),
                List.of(project("Hillcrest school block", 42), project("Riverside clinic", 36),
                        project("Lakeview hostel", 33), project("Northfield depot", 31)),
                List.of(new CompanyProfile.EquipmentItem("Batching plant", 1, null),
                        new CompanyProfile.EquipmentItem("Tower crane", 2, null),
                        new CompanyProfile.EquipmentItem("Transit mixer", 3, null)),
                List.of(person("Project manager"), person("Planning engineer"), person("Quality engineer"),
                        person("Site engineer")));
    }

    static CompanyProfile.SimilarProject project(String name, long crore) {
        return new CompanyProfile.SimilarProject(name, "Northfield Municipal Council",
                CRORE.multiply(BigDecimal.valueOf(crore)), 2024, null);
    }

    static CompanyProfile.PersonnelEntry person(String role) {
        return new CompanyProfile.PersonnelEntry(role, null, null, null);
    }

    CompanyEvidenceDto evidence(EvidenceSection section, String label) {
        when(storage.generateUploadUrlForKey(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(inv -> new PresignedUpload(inv.getArgument(0), "https://store.test/put", inv.getArgument(1), 3600));
        EvidenceUploadDto upload = qualification.presignEvidence(new PresignEvidenceRequest(section, label,
                "certificate.pdf", "application/pdf", 1024L));
        when(storage.objectExists(upload.upload().key())).thenReturn(true);
        return qualification.registerEvidence(upload.evidenceId(), new RegisterDocumentRequest(upload.upload().key()));
    }

    @BeforeEach
    void storageIssuesUploadsAndAttachmentsRegister() {
        when(storage.generateUploadUrlForKey(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(inv -> new PresignedUpload(inv.getArgument(0), "https://store.test/put", inv.getArgument(1), 3600));
        Attachment attachment = new Attachment();
        attachment.setId(77L);
        when(attachments.registerUploads(any(), any(AttachmentOwner.class), anyString())).thenReturn(List.of(attachment));
    }

    UUID nitPdf(UUID pkg) {
        return pdf(pkg, "Notice inviting tender.pdf",
                page(1, "NOTICE INVITING TENDER",
                        "3.1 The bidder shall have a minimum annual turnover of Rs. 100 Cr.",
                        "3.2 The bidder shall have completed 3 similar projects, each of value not less than Rs. 30 Cr.",
                        "3.3 The bidder shall have at least 7 years of experience in building works."),
                page(2, "3.4 Required equipment: one batching plant, two tower cranes and three transit mixers.",
                        "3.5 Required personnel: 5 key personnel, including a project manager and a safety officer."));
    }

    void classifyAndRead(UUID... docs) {
        for (UUID doc : docs) {
            jdbc.update("INSERT INTO doc_ingest_extract_job (id, organization_id, package_id, document_id, extractor_key, "
                    + "extractor_version, status, queued_at, created_at, updated_at) VALUES (gen_random_uuid(), ?, ?, ?, "
                    + "'classify', '1', 'QUEUED', now(), now(), now())", ORG, packageId, doc);
        }
        runAll();
        classification.list(packageId, 0, 50).getContent()
                .forEach(d -> assertThat(d.docClass()).as("classified by its heading").isEqualTo("NIT"));
        classification.confirmAll(packageId);
        runAll();
    }

    void acceptAndApplyAll() {
        review.decide(packageId, review.list(packageId, CandidateKind.QUALIFICATION_REQUIREMENT, CandidateStatus.PROPOSED,
                null, 0, 50).getContent().stream()
                .map(c -> new CandidateDecision(c.id(), CandidateDecision.Action.ACCEPT, null, null)).toList());
        assertThat(apply.apply(packageId, CandidateKind.QUALIFICATION_REQUIREMENT).applied()).isEqualTo(5);
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

    long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE organization_id = ?", Long.class, ORG);
    }

    UUID pkg(String stage) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_ingest_package (id, organization_id, name, stage, status, created_at, updated_at) "
                + "VALUES (?, ?, 'Hillcrest campus tender', ?, 'OPEN', now(), now())", id, ORG, stage);
        return id;
    }

    String prefix(UUID pkg, UUID doc) {
        return DocumentIngestionStorageLayout.outputPrefix(ORG, pkg, doc);
    }

    UUID pdf(UUID pkg, String name, String... pages) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_ingest_document (id, organization_id, package_id, source_key, output_prefix, sha256, "
                + "file_name, file_type, size_bytes, status, page_count, created_at, updated_at) VALUES (?, ?, ?, 'k', ?, ?, "
                + "?, 'PDF', 1, 'PARSED', ?, now(), now())", id, ORG, pkg, prefix(pkg, id), "b".repeat(64), name,
                pages.length);
        objects.put(prefix(pkg, id) + "meta.json", ("{\"fileType\": \"PDF\", \"pageCount\": " + pages.length
                + ", \"warnings\": []}").getBytes(StandardCharsets.UTF_8));
        objects.put(prefix(pkg, id) + "pages.jsonl", String.join("\n", pages).getBytes(StandardCharsets.UTF_8));
        objects.put(prefix(pkg, id) + "tables.jsonl", new byte[0]);
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
