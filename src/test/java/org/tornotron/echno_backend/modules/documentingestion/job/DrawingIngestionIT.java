package org.tornotron.echno_backend.modules.documentingestion.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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
import org.tornotron.echno_backend.modules.documentingestion.apply.DrawingApplier;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocClass;
import org.tornotron.echno_backend.modules.documentingestion.domain.DrawingEntry;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractionMethod;
import org.tornotron.echno_backend.modules.documentingestion.dto.ApplyResultDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.CandidateDecision;
import org.tornotron.echno_backend.modules.documentingestion.dto.CandidateDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.ClassificationRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.DrawingEntryDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.ProvenanceDto;
import org.tornotron.echno_backend.modules.documentingestion.extraction.CandidateWriter;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ClassificationWriter;
import org.tornotron.echno_backend.modules.documentingestion.extraction.classify.ClassificationExtractor;
import org.tornotron.echno_backend.modules.documentingestion.extraction.drawing.DrawingExtractor;
import org.tornotron.echno_backend.modules.documentingestion.extraction.drawing.DrawingFields;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkReader;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkRecorder;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractorRegistry;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.IngestLlmProperties;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.LlmBudget;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.LlmGate;
import org.tornotron.echno_backend.modules.documentingestion.extraction.quote.QuotedCandidates;
import org.tornotron.echno_backend.modules.documentingestion.parsing.ParsedOutputReader;
import org.tornotron.echno_backend.modules.documentingestion.review.ProvenanceService;
import org.tornotron.echno_backend.modules.documentingestion.review.ReviewService;
import org.tornotron.echno_backend.modules.documentingestion.service.ClassificationService;
import org.tornotron.echno_backend.modules.documentingestion.service.DrawingRegisterService;
import org.tornotron.echno_backend.modules.documentingestion.service.ExtractionService;
import org.tornotron.echno_backend.modules.documentingestion.service.IngestSettingsService;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Step 19 of the build plan, on the real migration with the LLM endpoint stubbed and the worker's outputs
 * served from a mocked object store: the done-when lines of design section 4.3. A 100-sheet PDF set gives
 * 100 candidates with the crop beside each; two revisions of one number link up with the newer current,
 * whichever is applied first; a DWG gives a register row that says it was not read. Also: a DXF read from
 * its attributes, a sheet the patterns cannot place read through the LLM on its crop only, a scanned title
 * block flagged, and an older worker's output with no title blocks named as not read.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@TestPropertySource(properties = {
        "echno.modules.document-ingestion.enabled=true",
        "echno.modules.document-ingestion.allowed-org-ids=" + DrawingIngestionIT.ORG,
        "echno.modules.document-ingestion.llm.initial-delay-millis=86400000"
})
@Import({ExtractJobDispatcher.class, ExtractJobWorker.class, ExtractJobQueue.class, ChunkReader.class,
        ChunkRecorder.class, LlmGate.class, LlmBudget.class, IngestLlmProperties.class, LlmEndpointProperties.class,
        ExtractorRegistry.class, ClassificationExtractor.class, ClassificationWriter.class, CandidateWriter.class,
        DrawingExtractor.class, ParsedOutputReader.class, IngestSettingsService.class, ClassificationService.class,
        ExtractionService.class, ReviewService.class, ApplyService.class, ApplyGuard.class, DrawingApplier.class,
        DrawingRegisterService.class, ProvenanceService.class,
        TenantScopedJobRunner.class, TransactionalWorkRunner.class, TransactionRetryTemplate.class,
        SimpleMeterRegistry.class, UserContextService.class, TenantEntityHelper.class,
        AopAutoConfiguration.class, HibernateFilterConfig.class, TenantIsolationListenerRegistrar.class,
        UnscopedAccessGuard.class})
class DrawingIngestionIT extends AbstractIntegrationTest {

    static final long ORG = 913_019_101L;
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
    DrawingRegisterService register;

    @Autowired
    IngestLlmProperties properties;

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
                + "organization_phone) VALUES (?, ?, 'address', ?, '0000000000')", ORG, "Drawings " + UUID.randomUUID(),
                UUID.randomUUID() + "@example.test");
        packageId = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_ingest_package (id, organization_id, name, stage, status, created_at, updated_at) "
                + "VALUES (?, ?, 'Riverside depot drawings', 'TENDER', 'OPEN', now(), now())", packageId, ORG);
        properties.setBackoff(List.of(Duration.ZERO, Duration.ZERO, Duration.ZERO));
        when(client.isAvailable()).thenReturn(true);
        when(client.complete(any())).thenAnswer(inv -> {
            ChatCompletionRequest req = inv.getArgument(0);
            if (!req.systemPrompt().equals(DrawingExtractor.PROMPT.system())) {
                return ok("{\"pages\": []}");
            }
            sent.add(req);
            return ok(LLM_ANSWER);
        });
        when(storage.readObject(any(), anyLong())).thenAnswer(inv -> Optional.ofNullable(objects.get(inv.<String>getArgument(0)))
                .map(b -> new FileStorageService.StoredObject(inv.getArgument(0), "application/json", b)));
        when(storage.objectExists(anyString())).thenAnswer(inv -> objects.containsKey(inv.<String>getArgument(0)));
        when(storage.generateDownloadUrl(anyString(), any())).thenAnswer(inv -> "https://store.example.test/"
                + inv.getArgument(0));
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("se", "n/a", "ROLE_USER"));
        TenantContext.setCurrentOrgId(ORG);
    }

    @AfterEach
    void removeRows() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        for (String table : List.of("doc_ingest_provenance", "doc_ingest_llm_call", "doc_ingest_source_ref",
                "doc_ingest_drawing", "doc_ingest_candidate", "doc_ingest_extract_job", "doc_ingest_segment",
                "doc_ingest_document", "doc_ingest_package")) {
            jdbc.update("DELETE FROM " + table + " WHERE organization_id = ?", ORG);
        }
        jdbc.update("DELETE FROM organization WHERE id = ?", ORG);
    }

    static final String LLM_ANSWER = """
            {"drawings": [{"page": 2,
              "drawing_number": {"value": "RCB-ME-410", "quote": "RCB-ME-410 C"},
              "title": {"value": "PUMP ROOM LAYOUT", "quote": "PUMP ROOM LAYOUT"},
              "revision": {"value": "C", "quote": "RCB-ME-410 C"}}]}""";

    @Test
    void aHundredSheetSetGivesAHundredCandidatesEachWithItsCrop() {
        List<Block> sheets = new ArrayList<>();
        for (int n = 1; n <= 100; n++) {
            sheets.add(new Block(n, "TEXT").row(0.84, 0.80, "TITLE").row(0.85, 0.80, "LEVEL", String.valueOf(n), "PLAN")
                    .row(0.88, 0.80, "DRAWING", "NO.").row(0.88, 0.93, "REV")
                    .row(0.89, 0.80, "RCB-AR-" + (100 + n)).row(0.89, 0.93, "A")
                    .row(0.92, 0.80, "SCALE:", "1:100", "DATE:", "12.03.2026"));
        }
        UUID set = pdf("Architectural set.pdf", sheets, true);

        readAsDrawings(set);

        assertThat(sent).as("every sheet read by the patterns, none sent").isEmpty();
        List<CandidateDto> drawings = drawings();
        assertThat(drawings).hasSize(100);
        assertThat(drawings).allSatisfy(c -> {
            assertThat(c.method()).isEqualTo(ExtractionMethod.DETERMINISTIC);
            assertThat(c.flags()).isEmpty();
            int page = ((Number) c.payload().get(DrawingFields.SHEET_PAGE)).intValue();
            assertThat(c.naturalKey()).isEqualTo("RCB-AR-" + (100 + page));
            assertThat(c.payload()).containsEntry(DrawingFields.CROP_IMAGE, "titleblock/" + page + ".webp")
                    .containsEntry(DrawingFields.REVISION, "A").containsEntry(DrawingFields.TITLE, "LEVEL " + page + " PLAN")
                    .containsEntry(DrawingFields.SCALE, "1:100").containsEntry(DrawingFields.DISCIPLINE, "Architectural");
            assertThat(c.sources()).isNotEmpty().allSatisfy(s -> {
                assertThat(s.page()).isEqualTo(page);
                assertThat(s.box()).isNotNull();
            });
        });
        assertThat(register.cropUrl(set, 7).url()).isEqualTo("https://store.example.test/" + prefix(set)
                + "titleblock/7.webp");
        assertThat(jdbc.queryForObject("SELECT status FROM doc_ingest_extract_job WHERE document_id = ? AND "
                + "extractor_key = 'drawings'", String.class, set)).isEqualTo("DONE");

        ApplyResultDto applied = acceptAndApply(drawings, List.of());
        assertThat(applied.applied()).isEqualTo(100);
        assertThat(register.list(packageId, true, 0, 200).getTotalElements()).isEqualTo(100);
    }

    @Test
    void twoRevisionsOfOneNumberLinkUpWithTheNewerCurrentWhicheverIsAppliedFirst() {
        UUID first = pdf("Issue 1.pdf", List.of(sheet(1, "RCB-AR-101", "A", "01.02.2026"),
                sheet(2, "RCB-AR-102", "A", "01.02.2026")), true);
        UUID second = pdf("Issue 2.pdf", List.of(sheet(1, "rcb-ar-101", "B", "12.03.2026")), true);
        readAsDrawings(first, second);
        List<CandidateDto> all = drawings();
        assertThat(all).hasSize(3);
        CandidateDto revB = all.stream().filter(c -> c.documentId().equals(second)).findFirst().orElseThrow();

        acceptAndApply(List.of(revB), List.of());
        acceptAndApply(all.stream().filter(c -> !c.id().equals(revB.id())).toList(), List.of());

        List<DrawingEntryDto> rows = register.list(packageId, false, 0, 50).getContent();
        assertThat(rows).hasSize(3);
        DrawingEntryDto b = rows.stream().filter(r -> "B".equals(r.revision())).findFirst().orElseThrow();
        DrawingEntryDto a = rows.stream().filter(r -> "A".equals(r.revision()) && r.drawingNumber().equals("RCB-AR-101"))
                .findFirst().orElseThrow();
        assertThat(b.current()).isTrue();
        assertThat(a.current()).as("the older revision is kept, not current").isFalse();
        assertThat(a.supersededById()).isEqualTo(b.id());
        assertThat(b.supersedesId()).isEqualTo(a.id());
        assertThat(b.revisionDate()).hasToString("2026-03-12");
        assertThat(b.cropAvailable()).isTrue();
        assertThat(b.sheetPage()).isEqualTo(1);
        assertThat(register.revisions(b.id())).extracting(DrawingEntryDto::revision).containsExactly("A", "B");
        assertThat(register.revisions(a.id())).extracting(DrawingEntryDto::id).containsExactly(a.id(), b.id());
        assertThat(register.list(packageId, true, 0, 50).getContent()).extracting(DrawingEntryDto::drawingNumber)
                .containsExactlyInAnyOrder("rcb-ar-101", "RCB-AR-102");
        List<ProvenanceDto> onB = provenance.forTarget(DrawingApplier.TARGET, b.id().toString());
        assertThat(onB).extracting(ProvenanceDto::targetField)
                .contains("drawingNumber", "revision", "title", "revisionDate", "scale");
        assertThat(onB).allSatisfy(p -> assertThat(p.source()).isNotNull());
    }

    @Test
    void aDwgGivesARegisterRowThatSaysItWasNotRead() {
        UUID dwg = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_ingest_document (id, organization_id, package_id, source_key, output_prefix, file_name, "
                + "file_type, size_bytes, status, doc_class, class_method, class_reason, created_at, updated_at) VALUES "
                + "(?, ?, ?, 'k', ?, 'HRV-AR-301.dwg', 'DWG', 1, 'STORED_ONLY', 'DRAWING', 'DETERMINISTIC', "
                + "'A drawing file.', now(), now())", dwg, ORG, packageId, prefix(dwg));

        assertThat(classification.confirmAll(packageId).jobsQueued()).isEqualTo(1);
        runAll();

        CandidateDto c = drawings().stream().filter(d -> d.documentId().equals(dwg)).findFirst().orElseThrow();
        assertThat(c.naturalKey()).isEqualTo("HRV-AR-301");
        assertThat(c.flags()).extracting(f -> f.code())
                .containsExactly(DrawingFields.NOT_READ, DrawingFields.NUMBER_FROM_FILE_NAME);
        verify(storage, never()).readObject(org.mockito.ArgumentMatchers.startsWith(prefix(dwg)), anyLong());
        acceptAndApply(List.of(c), List.of(DrawingFields.NOT_READ, DrawingFields.NUMBER_FROM_FILE_NAME));

        DrawingEntryDto row = register.list(packageId, false, 0, 10).getContent().get(0);
        assertThat(row.status()).isEqualTo(DrawingEntry.Status.NOT_READ);
        assertThat(row.note()).isEqualTo("Not read, add a PDF or DXF copy.");
        assertThat(row.drawingNumber()).isEqualTo("HRV-AR-301");
        assertThat(row.sourceFormat()).isEqualTo("DWG");
        assertThat(row.current()).isTrue();
        assertThat(row.cropAvailable()).isFalse();
    }

    @Test
    void aDxfIsReadFromItsTitleBlockAttributesWithNoLlm() {
        UUID dxf = document("Ground floor.dxf", "DXF", null);
        objects.put(prefix(dxf) + "dxf.json", ("{\"dxfVersion\": \"AC1032\", \"layouts\": [{\"name\": \"Model\", "
                + "\"modelSpace\": true}, {\"name\": \"A1 Sheet\", \"modelSpace\": false}], \"inserts\": [{\"layout\": "
                + "\"A1 Sheet\", \"block\": \"TITLE_BLOCK_A1\", \"titleBlockLike\": true, \"attributes\": ["
                + "{\"tag\": \"DWG_NO\", \"value\": \"HRV-AR-101\"}, {\"tag\": \"TITLE\", \"value\": \"GROUND FLOOR PLAN\"}, "
                + "{\"tag\": \"REV\", \"value\": \"C\"}, {\"tag\": \"DATE\", \"value\": \"2026-04-02\"}]}], "
                + "\"truncated\": false}").getBytes(StandardCharsets.UTF_8));
        jdbc.update("UPDATE doc_ingest_document SET doc_class = 'DRAWING', class_method = 'DETERMINISTIC' WHERE id = ?", dxf);

        classification.confirmAll(packageId);
        runAll();

        assertThat(sent).isEmpty();
        CandidateDto c = drawings().stream().filter(d -> d.documentId().equals(dxf)).findFirst().orElseThrow();
        assertThat(c.payload()).containsEntry(DrawingFields.NUMBER, "HRV-AR-101").containsEntry(DrawingFields.REVISION, "C")
                .containsEntry(DrawingFields.LAYOUT, "A1 Sheet").containsEntry(DrawingFields.SOURCE_FORMAT, "DXF");
        assertThat(c.sources()).filteredOn(s -> s.field().equals(DrawingFields.NUMBER)).singleElement().satisfies(s -> {
            assertThat(s.sheet()).isEqualTo("A1 Sheet");
            assertThat(s.cell()).isEqualTo("DWG_NO");
        });
        acceptAndApply(List.of(c), List.of());
        DrawingEntryDto row = register.list(packageId, false, 0, 10).getContent().get(0);
        assertThat(row.layout()).isEqualTo("A1 Sheet");
        assertThat(row.revisionDate()).hasToString("2026-04-02");
        assertThat(row.status()).isEqualTo(DrawingEntry.Status.READ);
    }

    @Test
    void aSheetThePatternsCannotPlaceIsReadFromItsCropAloneAndAScannedOneIsFlagged() {
        UUID mixed = pdf("Services.pdf", List.of(
                new Block(1, "OCR").row(0.88, 0.80, "DRAWING", "NO.", "RCB-ME-401", "REV", "B"),
                new Block(2, "TEXT").row(0.85, 0.80, "PUMP", "ROOM", "LAYOUT").row(0.86, 0.80, "RCB-ME-410", "C")),
                true);
        UUID old = pdf("Old reading.pdf", List.of(sheet(1, "RCB-ME-420", "A", "01.01.2026")), false);

        readAsDrawings(mixed, old);

        assertThat(sent).as("one call, for the one sheet the patterns could not place").singleElement()
                .satisfies(req -> {
                    assertThat(req.userPrompt()).contains("[page 2]").contains("PUMP ROOM LAYOUT")
                            .doesNotContain("SHEET 2 MAIN TEXT").doesNotContain("RCB-ME-401");
                });
        Map<String, CandidateDto> byNumber = new HashMap<>();
        drawings().forEach(c -> byNumber.put(c.naturalKey(), c));
        assertThat(byNumber).containsOnlyKeys("RCB-ME-401", "RCB-ME-410");
        assertThat(byNumber.get("RCB-ME-401").method()).isEqualTo(ExtractionMethod.DETERMINISTIC);
        assertThat(byNumber.get("RCB-ME-401").flags()).extracting(f -> f.code()).containsExactly(QuotedCandidates.OCR_PAGE);
        CandidateDto read = byNumber.get("RCB-ME-410");
        assertThat(read.method()).isEqualTo(ExtractionMethod.LLM);
        assertThat(read.flags()).isEmpty();
        assertThat(read.payload()).containsEntry(DrawingFields.TITLE, "PUMP ROOM LAYOUT")
                .containsEntry(DrawingFields.CROP_IMAGE, "titleblock/2.webp");
        assertThat(read.sources()).allSatisfy(s -> assertThat(s.quoteVerified()).isTrue());
        String unread = jdbc.queryForObject("SELECT CAST(s.unread_pages AS STRING) FROM doc_ingest_segment s "
                + "WHERE s.document_id = ?", String.class, old);
        assertThat(unread).contains(DrawingExtractor.TITLE_BLOCK_NOT_READ);
    }

    void readAsDrawings(UUID... docs) {
        for (UUID doc : docs) {
            classification.confirm(doc, new ClassificationRequest(DocClass.DRAWING, null));
        }
        runAll();
    }

    ApplyResultDto acceptAndApply(List<CandidateDto> rows, List<String> acknowledged) {
        for (int i = 0; i < rows.size(); i += 50) {
            review.decide(packageId, rows.subList(i, Math.min(i + 50, rows.size())).stream()
                    .map(c -> new CandidateDecision(c.id(), CandidateDecision.Action.ACCEPT, null,
                            acknowledged.isEmpty() ? null : acknowledged))
                    .toList());
        }
        return apply.apply(packageId, CandidateKind.DRAWING);
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

    List<CandidateDto> drawings() {
        return review.list(packageId, CandidateKind.DRAWING, null, null, 0, 200).getContent();
    }

    String prefix(UUID doc) {
        return DocumentIngestionStorageLayout.outputPrefix(ORG, packageId, doc);
    }

    static Block sheet(int page, String number, String revision, String date) {
        return new Block(page, "TEXT").row(0.84, 0.80, "TITLE").row(0.85, 0.80, "GENERAL", "ARRANGEMENT")
                .row(0.88, 0.80, "DRAWING", "NO.", number, "REV", revision)
                .row(0.92, 0.80, "SCALE", "1:200", "DATE", date);
    }

    UUID document(String name, String type, Integer pages) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_ingest_document (id, organization_id, package_id, source_key, output_prefix, sha256, "
                + "file_name, file_type, size_bytes, status, page_count, created_at, updated_at) VALUES (?, ?, ?, 'k', ?, ?, "
                + "?, ?, 1, 'PARSED', ?, now(), now())", id, ORG, packageId, prefix(id), "d".repeat(64), name, type, pages);
        objects.put(prefix(id) + "meta.json", ("{\"fileType\": \"" + type + "\", \"pageCount\": " + pages
                + ", \"warnings\": []}").getBytes(StandardCharsets.UTF_8));
        objects.put(prefix(id) + "tables.jsonl", new byte[0]);
        return id;
    }

    /** A PDF of drawing sheets: each page's text, and its title block unless {@code withTitleBlocks} is false. */
    UUID pdf(String name, List<Block> sheets, boolean withTitleBlocks) {
        UUID id = document(name, "PDF", sheets.size());
        List<String> pages = new ArrayList<>();
        List<String> blocks = new ArrayList<>();
        for (Block b : sheets) {
            pages.add("{\"page\": " + b.page + ", \"method\": \"" + b.method + "\", \"width\": 1190.0, \"height\": 842.0, "
                    + "\"lines\": [{\"text\": \"SHEET " + b.page + " MAIN TEXT\", \"box\": [0.1, 0.1, 0.5, 0.12], "
                    + "\"ocrConfidence\": null}], \"status\": \"OK\", \"reason\": null, \"script\": \"LATIN\"}");
            blocks.add(b.json());
            objects.put(prefix(id) + "titleblock/" + b.page + ".webp", new byte[] {1});
        }
        objects.put(prefix(id) + "pages.jsonl", String.join("\n", pages).getBytes(StandardCharsets.UTF_8));
        if (withTitleBlocks) {
            objects.put(prefix(id) + "titleblock.jsonl", String.join("\n", blocks).getBytes(StandardCharsets.UTF_8));
        }
        return id;
    }

    /** One title block in the worker's shape, its words placed left to right on rows. */
    static final class Block {

        final int page;
        final String method;
        final List<String> words = new ArrayList<>();
        final Map<Double, StringBuilder> lines = new java.util.LinkedHashMap<>();

        Block(int page, String method) {
            this.page = page;
            this.method = method;
        }

        Block row(double y, double x, String... texts) {
            double at = x;
            for (String t : texts) {
                double w = 0.006 * t.length();
                words.add("{\"text\": \"" + t + "\", \"box\": [" + at + ", " + y + ", " + (at + w) + ", " + (y + 0.008)
                        + "], \"ocrConfidence\": " + ("OCR".equals(method) ? "0.9" : "null") + "}");
                StringBuilder line = lines.computeIfAbsent(y, k -> new StringBuilder());
                line.append(line.isEmpty() ? "" : " ").append(t);
                at += w + 0.004;
            }
            return this;
        }

        String json() {
            List<String> l = new ArrayList<>();
            lines.forEach((y, text) -> l.add("{\"text\": \"" + text + "\", \"box\": [0.78, " + y + ", 0.99, " + (y + 0.008)
                    + "], \"ocrConfidence\": null}"));
            return "{\"page\": " + page + ", \"status\": \"OK\", \"reason\": null, \"method\": \"" + method + "\", "
                    + "\"region\": [0.75, 0.6, 1.0, 1.0], \"regionSource\": \"RULED_BOX\", \"image\": \"titleblock/"
                    + page + ".webp\", \"words\": [" + String.join(", ", words) + "], \"lines\": [" + String.join(", ", l)
                    + "]}";
        }
    }

    static ChatCompletionResult ok(String content) {
        return new ChatCompletionResult(ChatCompletionOutcome.OK, 200, content, "stop", 100, 20, 120, "test-model",
                Duration.ofMillis(7), null, "{}");
    }
}
