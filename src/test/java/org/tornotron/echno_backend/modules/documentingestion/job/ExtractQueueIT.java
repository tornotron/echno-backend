package org.tornotron.echno_backend.modules.documentingestion.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
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
import org.tornotron.echno_backend.modules.documentingestion.api.DocumentTextReady;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocClass;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractJob;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractJobStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.UnreadPage;
import org.tornotron.echno_backend.modules.documentingestion.dto.CandidateDecision;
import org.tornotron.echno_backend.modules.documentingestion.dto.CandidateDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.ConfirmAllResultDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.DecisionOutcomeDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.DocumentClassificationDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.LlmBudgetRequest;
import org.tornotron.echno_backend.modules.documentingestion.extraction.CandidateWriter;
import org.tornotron.echno_backend.modules.documentingestion.extraction.ClassificationWriter;
import org.tornotron.echno_backend.modules.documentingestion.extraction.classify.ClassificationExtractor;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkOutput;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkPages;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkReader;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkRecorder;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.EnglishPages;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractCompletion;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractContext;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractPlan;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.Extractor;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractorRegistry;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.IngestLlmProperties;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.LlmBudget;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.LlmGate;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.LlmJson;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.PromptSpec;
import org.tornotron.echno_backend.modules.documentingestion.extraction.quote.QuotedCandidates;
import org.tornotron.echno_backend.modules.documentingestion.parsing.PageText;
import org.tornotron.echno_backend.modules.documentingestion.parsing.ParsedOutputReader;
import org.tornotron.echno_backend.modules.documentingestion.repository.ExtractJobRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.LlmCallRepository;
import org.tornotron.echno_backend.modules.documentingestion.review.ReviewService;
import org.tornotron.echno_backend.modules.documentingestion.service.ClassificationService;
import org.tornotron.echno_backend.modules.documentingestion.service.DocumentIngestionLimits;
import org.tornotron.echno_backend.modules.documentingestion.service.ExtractionService;
import org.tornotron.echno_backend.modules.documentingestion.service.IngestSettingsService;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Step 12 of the build plan on the real migration, with the LLM endpoint stubbed and the worker's
 * outputs served from a mocked object store in the contract's shape. Everything between is real: the
 * claim, the lease, the chunk retries and splits, the ledger, the budget, the LLM switch, classification
 * and its confirmation, and the tenant filter wired as in the application. A test extractor reads GCC
 * segments into candidates, as the per-kind extractors of later steps will.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@RecordApplicationEvents
@TestPropertySource(properties = {
        "echno.modules.document-ingestion.enabled=true",
        "echno.modules.document-ingestion.allowed-org-ids=" + ExtractQueueIT.ORG_A + "," + ExtractQueueIT.ORG_B,
        // Passes run by hand: the schedule would otherwise poll behind the test.
        "echno.modules.document-ingestion.llm.initial-delay-millis=86400000"
})
@Import({ExtractJobDispatcher.class, ExtractJobWorker.class, ExtractJobQueue.class, ChunkReader.class,
        ChunkRecorder.class, LlmGate.class, LlmBudget.class, IngestLlmProperties.class, LlmEndpointProperties.class,
        ExtractorRegistry.class, ClassificationExtractor.class, ClassificationWriter.class, CandidateWriter.class,
        ParsedOutputReader.class, ParseJobIngestor.class, DocumentIngestionLimits.class, IngestSettingsService.class,
        ClassificationService.class, ExtractionService.class, ReviewService.class, ExtractQueueIT.ProbeExtractor.class,
        TenantScopedJobRunner.class, TransactionalWorkRunner.class, TransactionRetryTemplate.class,
        SimpleMeterRegistry.class, UserContextService.class, TenantEntityHelper.class,
        AopAutoConfiguration.class, HibernateFilterConfig.class, TenantIsolationListenerRegistrar.class,
        UnscopedAccessGuard.class})
class ExtractQueueIT extends AbstractIntegrationTest {

    static final long ORG_A = 912_001_201L;
    static final long ORG_B = 912_001_202L;
    static final long ORG_OFF_LIST = 912_001_203L;
    static final Executor DIRECT = Runnable::run;
    static final Pattern PAGES = Pattern.compile("\\[pages ([0-9,]+)]");

    /**
     * Reads GCC segments: each page's line "Item n costs ..." becomes one candidate with a name and an
     * amount, as the answer states them. Synthetic, and only here.
     */
    @TestConfiguration
    static class ProbeExtractor implements Extractor {

        static final PromptSpec PROMPT = new PromptSpec("probe-1", "Read items. Synthetic test prompt.");
        private final ObjectMapper json = new ObjectMapper();

        @Override
        public String key() {
            return "probe";
        }

        @Override
        public String version() {
            return "1";
        }

        @Override
        public PromptSpec prompt() {
            return PROMPT;
        }

        @Override
        public Set<DocClass> segmentClasses() {
            return Set.of(DocClass.GCC);
        }

        @Override
        public ExtractPlan plan(ExtractContext context) {
            List<PageText> send = new ArrayList<>();
            List<UnreadPage> unread = new ArrayList<>();
            for (PageText p : context.pages()) {
                UnreadPage notSent = EnglishPages.notSent(p);
                if (notSent != null) {
                    unread.add(notSent);
                } else if (EnglishPages.hasText(p)) {
                    send.add(p);
                }
            }
            return new ExtractPlan(send, unread, Map.of());
        }

        @Override
        public String userPrompt(ExtractContext context, ChunkPages chunk) {
            StringBuilder sb = new StringBuilder("[pages ");
            sb.append(String.join(",", chunk.pageNumbers().stream().map(String::valueOf).toList())).append("]\n");
            chunk.pages().forEach(p -> sb.append(p.text()).append('\n'));
            return sb.toString();
        }

        @Override
        public ChunkOutput read(ExtractContext context, ChunkPages chunk, String answer) {
            List<org.tornotron.echno_backend.modules.documentingestion.extraction.ProposedCandidate> out = new ArrayList<>();
            for (var v : LlmJson.object(answer, json).path("values")) {
                var c = QuotedCandidates.build(context, chunk, CandidateKind.PROJECT_FIELD, v.path("key").asText(), List.of(
                        new QuotedCandidates.LlmValue("name", v.path("name").asText(), v.path("page").asInt(),
                                v.path("quote").asText()),
                        new QuotedCandidates.LlmValue("amount", v.path("amount").asText(), v.path("page").asInt(),
                                v.path("quote").asText())));
                if (c != null) {
                    out.add(c);
                }
            }
            return new ChunkOutput(out, Map.of());
        }

        @Override
        public void complete(ExtractContext context, ExtractCompletion completion) {
        }
    }

    @Autowired
    ExtractJobDispatcher dispatcher;

    @Autowired
    ExtractJobRepository jobs;

    @Autowired
    LlmCallRepository ledger;

    @Autowired
    ExtractJobQueue queue;

    @Autowired
    ProbeExtractor probe;

    @Autowired
    IngestLlmProperties properties;

    @Autowired
    ClassificationService classification;

    @Autowired
    ExtractionService extraction;

    @Autowired
    ReviewService review;

    @Autowired
    ParseJobIngestor ingestor;

    @Autowired
    TransactionRetryTemplate retry;

    @Autowired
    TenantEntityHelper tenantEntityHelper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ApplicationEvents events;

    @MockitoBean
    ChatCompletionClient client;

    @MockitoBean
    FileStorageService storage;

    @MockitoBean(name = "orgSecurity")
    OrganizationSecurityService orgSecurity;

    /** The stubbed endpoint: what each call was sent, and how to answer it. */
    final List<ChatCompletionRequest> sent = Collections.synchronizedList(new ArrayList<>());
    Function<ChatCompletionRequest, ChatCompletionResult> answer;
    final Map<String, byte[]> objects = new HashMap<>();
    UUID packageId;

    @BeforeEach
    void anEndpointAndTwoOrganizations() {
        for (long org : List.of(ORG_A, ORG_B, ORG_OFF_LIST)) {
            jdbc.update("INSERT INTO organization (id, organization_name, organization_address, organization_email, "
                    + "organization_phone) VALUES (?, ?, 'address', ?, '0000000000')", org, "Extract " + UUID.randomUUID(),
                    UUID.randomUUID() + "@example.test");
        }
        packageId = pkg(ORG_A);
        properties.setBackoff(List.of(Duration.ZERO, Duration.ZERO, Duration.ZERO));
        properties.setCallsPerJob(4);
        when(client.isAvailable()).thenReturn(true);
        answer = this::itemsAnswer;
        when(client.complete(any())).thenAnswer(inv -> {
            ChatCompletionRequest req = inv.getArgument(0);
            sent.add(req);
            return answer.apply(req);
        });
        when(storage.readObject(any(), anyLong())).thenAnswer(inv -> Optional.ofNullable(objects.get(inv.<String>getArgument(0)))
                .map(b -> new FileStorageService.StoredObject(inv.getArgument(0), "application/json", b)));
        when(storage.objectExists(argThat(k -> k != null && k.endsWith(DocumentIngestionStorageLayout.META_FILE))))
                .thenReturn(true);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class))).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("pm", "n/a", "ROLE_USER"));
        TenantContext.setCurrentOrgId(ORG_A);
    }

    @AfterEach
    void removeRows() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        for (long org : List.of(ORG_A, ORG_B, ORG_OFF_LIST)) {
            for (String table : List.of("doc_ingest_llm_call", "doc_ingest_source_ref", "doc_ingest_candidate",
                    "doc_ingest_extract_job", "doc_ingest_parse_job", "doc_ingest_segment", "doc_ingest_document",
                    "doc_ingest_setting_change", "doc_ingest_org_settings", "doc_ingest_package")) {
                jdbc.update("DELETE FROM " + table + " WHERE organization_id = ?", org);
            }
            jdbc.update("DELETE FROM organization WHERE id = ?", org);
        }
    }

    // --------------------------------------------------------------------------------------------
    // Classification and confirmation
    // --------------------------------------------------------------------------------------------

    @Test
    void headingsSplitAPdfIntoSegmentsWithNoLlmCallAndConfirmAllQueuesTheReaders() {
        UUID doc = pdf(ORG_A, packageId, "Package A.pdf", page(1, "NOTICE INVITING TENDER", "Works at site"),
                page(2, "The bidder shall quote rates."), page(3, "GENERAL CONDITIONS OF CONTRACT", "1. Definitions"),
                page(4, "Item 4 costs 1,250.00"));
        UUID job = queueClassify(ORG_A, doc);

        runAll();

        assertThat(status(job)).isEqualTo(ExtractJobStatus.DONE);
        verify(client, never()).complete(any());
        DocumentClassificationDto proposed = classification.list(packageId, 0, 50).getContent().get(0);
        assertThat(proposed.docClass()).isEqualTo("NIT");
        assertThat(proposed.confirmed()).isFalse();
        assertThat(proposed.segments()).extracting(s -> s.docClass() + " " + s.pageFrom() + "-" + s.pageTo())
                .containsExactly("NIT 1-2", "GCC 3-4");

        ConfirmAllResultDto confirmed = classification.confirmAll(packageId);

        assertThat(confirmed.documentsConfirmed()).isEqualTo(1);
        assertThat(confirmed.segmentsConfirmed()).isEqualTo(2);
        assertThat(confirmed.jobsQueued()).as("the probe reads GCC").isEqualTo(1);
        assertThat(classification.list(packageId, 0, 50).getContent().get(0).segments())
                .allSatisfy(s -> assertThat(s.confirmedAt()).isNotNull());
        runAll();
        assertThat(candidateCount(ORG_A)).as("one candidate per page of the GCC segment").isEqualTo(2);
    }

    @Test
    void aSegmentIsNotReadUntilAPersonConfirmsIt() {
        UUID doc = pdf(ORG_A, packageId, "Package A.pdf", page(1, "GENERAL CONDITIONS OF CONTRACT"),
                page(2, "Item 2 costs 1,250.00"));
        queueClassify(ORG_A, doc);
        runAll();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM doc_ingest_extract_job WHERE extractor_key = 'probe' "
                + "AND organization_id = ?", Long.class, ORG_A)).isZero();
        assertThat(sent).isEmpty();
    }

    @Test
    void aPdfWithNoPlainClassGoesToTheLlmWhichNamesWherePartsBegin() {
        UUID doc = pdf(ORG_A, packageId, "scan-0042.pdf", page(1, "Section one", "Works at site"),
                page(2, "Section two", "Clauses follow"), page(3, "Section three"));
        answer = req -> ok("{\"pages\": [{\"page\": 1, \"class\": \"NIT\", \"quote\": \"Section one\"},"
                + "{\"page\": 2, \"class\": \"GCC\", \"quote\": \"Section two\"},"
                + "{\"page\": 3, \"class\": \"BOQ\", \"quote\": \"An invented line\"}]}");
        queueClassify(ORG_A, doc);

        runAll();

        DocumentClassificationDto proposed = classification.list(packageId, 0, 50).getContent().get(0);
        assertThat(proposed.segments()).extracting(s -> s.docClass() + " " + s.pageFrom() + "-" + s.pageTo())
                .as("page 3's quote is not on page 3, so it continues page 2's part")
                .containsExactly("NIT 1-1", "GCC 2-3");
        assertThat(proposed.classMethod()).hasToString("LLM");
        assertThat(sent).singleElement().satisfies(r -> assertThat(r.systemPrompt()).isEqualTo(ClassificationExtractor.PROMPT.system()));
    }

    @Test
    void aWorkbookIsClassifiedWithLlmReadingOff() {
        readingOff(ORG_A);
        UUID doc = workbook(ORG_A, packageId, "Tender BOQ.xlsx");
        UUID job = queueClassify(ORG_A, doc);

        runAll();

        assertThat(status(job)).isEqualTo(ExtractJobStatus.DONE);
        assertThat(classification.list(packageId, 0, 50).getContent().get(0).docClass()).isEqualTo("BOQ");
        verify(client, never()).complete(any());
    }

    @Test
    void aParsedDocumentQueuesItsClassificationAndSaysItsTextIsReady() {
        UUID doc = pdf(ORG_A, packageId, "Package A.pdf", page(1, "NOTICE INVITING TENDER"));
        UUID parseJob = UUID.randomUUID();
        jdbc.update("UPDATE doc_ingest_document SET status = 'QUEUED' WHERE id = ?", doc);
        jdbc.update("INSERT INTO doc_ingest_parse_job (id, organization_id, package_id, document_id, job_type, status, "
                + "source_key, source_file_name, output_prefix, attempt, max_attempts, queued_at, finished_at, "
                + "ingest_claimed_by, created_at, updated_at) VALUES (?, ?, ?, ?, 'PARSE', 'DONE', 'k', 'Package A.pdf', ?, 1, 3, "
                + "now(), now(), 'r1', now(), now())", parseJob, ORG_A, packageId, doc, prefix(ORG_A, packageId, doc));

        retry.executeWithoutResult("test.ingest", () -> ingestor.ingest(parseJob, "r1"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM doc_ingest_extract_job WHERE document_id = ? "
                + "AND extractor_key = 'classify' AND status = 'QUEUED'", Long.class, doc)).isEqualTo(1);
        assertThat(events.stream(DocumentTextReady.class)).singleElement().satisfies(e -> {
            assertThat(e.documentId()).isEqualTo(doc);
            assertThat(e.organizationId()).isEqualTo(ORG_A);
            assertThat(e.outputPrefix()).isEqualTo(prefix(ORG_A, packageId, doc));
        });
    }

    // --------------------------------------------------------------------------------------------
    // Chunks: retries, splits, partial results
    // --------------------------------------------------------------------------------------------

    @Test
    void aRateLimitedCallIsRetriedAndEveryCallIsInTheLedger() {
        UUID job = probeJob(ORG_A, items(1, 10));
        List<String> limitedOnce = Collections.synchronizedList(new ArrayList<>());
        answer = req -> pagesOf(req).get(0) == 1 && limitedOnce.isEmpty() && limitedOnce.add("x")
                ? failed(ChatCompletionOutcome.RATE_LIMITED, 429) : itemsAnswer(req);

        runAll();

        assertThat(status(job)).isEqualTo(ExtractJobStatus.DONE);
        assertThat(ledgerRows(job)).containsExactly("1-8#1 RATE_LIMITED", "1-8#2 OK", "9-10#1 OK");
        assertThat(candidateCount(ORG_A)).isEqualTo(10);
    }

    @Test
    void aTruncatedAnswerSplitsItsChunkDownToOnePageAndIsNeverReadAsComplete() {
        UUID job = probeJob(ORG_A, items(1, 4));
        answer = req -> pagesOf(req).size() > 1 || pagesOf(req).get(0) == 4
                ? truncated() : itemsAnswer(req);

        runAll();

        assertThat(ledgerRows(job)).containsExactlyInAnyOrder("1-4#1 TRUNCATED", "1-2#1 TRUNCATED", "3-4#1 TRUNCATED",
                "1-1#1 OK", "2-2#1 OK", "3-3#1 OK", "4-4#1 TRUNCATED");
        ExtractJob read = job(job);
        assertThat(read.getStatus()).isEqualTo(ExtractJobStatus.PARTIAL);
        assertThat(read.getFailedPages()).extracting(UnreadPage::page).containsExactly(4);
        assertThat(candidateCount(ORG_A)).isEqualTo(3);
    }

    @Test
    void anAnswerThatIsNotJsonTwiceFailsItsChunkAndTheSegmentNamesThePagesNotRead() {
        UUID job = probeJob(ORG_A, items(1, 10));
        answer = req -> pagesOf(req).get(0) == 9 ? ok("Sorry, here are the items: none") : itemsAnswer(req);

        runAll();

        ExtractJob read = job(job);
        assertThat(read.getStatus()).isEqualTo(ExtractJobStatus.PARTIAL);
        assertThat(read.getFailedPages()).extracting(UnreadPage::page).containsExactly(9, 10);
        assertThat(read.getMessage()).contains("pages 9-10 not read");
        assertThat(ledgerRows(job)).containsExactly("1-8#1 OK", "9-10#1 INVALID_ANSWER", "9-10#2 INVALID_ANSWER");
        assertThat(jdbc.queryForObject("SELECT status FROM doc_ingest_segment WHERE id = ?", String.class,
                read.getSegmentId())).isEqualTo("PARTIAL");
        assertThat(jdbc.queryForObject("SELECT unread_pages::STRING FROM doc_ingest_segment WHERE id = ?", String.class,
                read.getSegmentId())).contains("\"page\": 9").contains("\"page\": 10").contains("READ_FAILED");
    }

    @Test
    void retryFailedPartsSendsOnlyTheChunksThatFailed() {
        UUID job = probeJob(ORG_A, items(1, 10));
        answer = req -> pagesOf(req).get(0) == 9 ? failed(ChatCompletionOutcome.SERVER_ERROR, 503) : itemsAnswer(req);
        runAll();
        assertThat(status(job)).isEqualTo(ExtractJobStatus.PARTIAL);
        sent.clear();
        answer = this::itemsAnswer;

        extraction.retryFailed(job);
        runAll();

        assertThat(status(job)).isEqualTo(ExtractJobStatus.DONE);
        assertThat(sent).singleElement().satisfies(r -> assertThat(pagesOf(r)).containsExactly(9, 10));
        assertThat(candidateCount(ORG_A)).isEqualTo(10);
    }

    @Test
    void aChunkReadAgainUpsertsItsCandidatesInsteadOfAddingRows() {
        UUID job = probeJob(ORG_A, items(1, 10));
        runAll();
        assertThat(candidateCount(ORG_A)).isEqualTo(10);
        jdbc.update("DELETE FROM doc_ingest_llm_call WHERE job_id = ?", job);
        jdbc.update("UPDATE doc_ingest_extract_job SET status = 'QUEUED' WHERE id = ?", job);

        runAll();

        assertThat(status(job)).isEqualTo(ExtractJobStatus.DONE);
        assertThat(candidateCount(ORG_A)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM doc_ingest_source_ref WHERE organization_id = ?", Long.class,
                ORG_A)).isEqualTo(20);
    }

    // --------------------------------------------------------------------------------------------
    // The quote check, end to end
    // --------------------------------------------------------------------------------------------

    @Test
    void anInventedQuoteIsKeptUnverifiedAndIsNeverAcceptedInBulk() {
        probeJob(ORG_A, items(1, 2));
        answer = req -> ok("{\"values\": ["
                + "{\"key\": \"k1\", \"name\": \"Item 1\", \"amount\": \"1,250.00\", \"page\": 1, \"quote\": \"Item 1 costs 1,250.00\"},"
                + "{\"key\": \"k2\", \"name\": \"Item 2\", \"amount\": \"1,250.00\", \"page\": 2, \"quote\": \"Item 2 is free at 1,250.00\"}]}");

        runAll();

        List<CandidateDto> rows = candidates();
        CandidateDto invented = rows.stream().filter(c -> c.naturalKey().equals("k2")).findFirst().orElseThrow();
        CandidateDto good = rows.stream().filter(c -> c.naturalKey().equals("k1")).findFirst().orElseThrow();
        assertThat(invented.sources()).allSatisfy(s -> assertThat(s.quoteVerified()).isFalse());
        assertThat(invented.flags()).extracting(f -> f.code()).contains(QuotedCandidates.QUOTE_NOT_FOUND);
        assertThat(good.sources()).allSatisfy(s -> {
            assertThat(s.quoteVerified()).isTrue();
            assertThat(s.box()).containsExactly(0.1, 0.2, 0.9, 0.25);
        });

        List<CandidateDecision> bulk = List.of(
                new CandidateDecision(good.id(), CandidateDecision.Action.ACCEPT, null, null),
                new CandidateDecision(invented.id(), CandidateDecision.Action.ACCEPT, null, List.of(QuotedCandidates.QUOTE_NOT_FOUND)));
        assertThat(review.decide(packageId, bulk)).extracting(DecisionOutcomeDto::outcome)
                .containsExactly(DecisionOutcomeDto.Outcome.ACCEPTED, DecisionOutcomeDto.Outcome.SKIPPED_FLAGGED);
        assertThat(review.decide(packageId, List.of(bulk.get(1)))).extracting(DecisionOutcomeDto::outcome)
                .containsExactly(DecisionOutcomeDto.Outcome.ACCEPTED);
    }

    @Test
    void aNumberThatIsNotInItsQuoteIsDropped() {
        probeJob(ORG_A, items(1, 1));
        answer = req -> ok("{\"values\": [{\"key\": \"k1\", \"name\": \"Item 1\", \"amount\": \"9,999.00\", \"page\": 1, "
                + "\"quote\": \"Item 1 costs 1,250.00\"}]}");

        runAll();

        assertThat(candidates()).singleElement().satisfies(c -> {
            assertThat(c.payload()).containsOnlyKeys("name");
            assertThat(c.sources()).extracting(s -> s.field()).containsExactly("name");
        });
    }

    // --------------------------------------------------------------------------------------------
    // English only, the ledger, tenants
    // --------------------------------------------------------------------------------------------

    @Test
    void aPageNotInEnglishIsNotSentAndIsShownAsNotRead() {
        String hindi = "निविदा की सामान्य शर्तें और अनुबंध की विशेष शर्तें यहाँ दी गई हैं";
        UUID job = probeJob(ORG_A, List.of(item(1), pageWithScript(2, "DEVANAGARI", hindi), item(3)));

        runAll();

        assertThat(sent).isNotEmpty().allSatisfy(r -> {
            assertThat(r.userPrompt()).doesNotContain(hindi);
            assertThat(pagesOf(r)).doesNotContain(2);
        });
        ExtractJob read = job(job);
        assertThat(read.getStatus()).isEqualTo(ExtractJobStatus.DONE);
        assertThat(read.getUnreadPages()).singleElement().satisfies(u -> {
            assertThat(u.page()).isEqualTo(2);
            assertThat(u.reason()).isEqualTo(UnreadPage.NOT_ENGLISH);
        });
        assertThat(jdbc.queryForObject("SELECT unread_pages::STRING FROM doc_ingest_segment WHERE id = ?", String.class,
                read.getSegmentId())).contains("NOT_ENGLISH");
    }

    @Test
    void theLedgerRecordsEveryCallAndHoldsNoDocumentText() {
        UUID job = probeJob(ORG_A, items(1, 10));
        runAll();

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM doc_ingest_llm_call WHERE job_id = ?", job);
        assertThat(rows).hasSize(2).allSatisfy(row -> {
            assertThat(row.get("prompt_version")).isEqualTo("probe-1");
            assertThat(row.get("model")).isEqualTo("test-model");
            assertThat(((Number) row.get("prompt_tokens")).intValue()).isEqualTo(100);
            assertThat(((Number) row.get("completion_tokens")).intValue()).isEqualTo(20);
            assertThat(row.get("estimated_cost_usd")).isNotNull();
            row.values().forEach(v -> assertThat(String.valueOf(v)).doesNotContain("costs").doesNotContain("Item"));
        });
        assertThat(rows).extracting(r -> r.get("pages")).containsExactlyInAnyOrder("1-8", "9-10");
        assertThat(jdbc.queryForList("SELECT data_type FROM information_schema.columns WHERE table_name = 'doc_ingest_llm_call'",
                String.class)).as("no column could hold page text").doesNotContain("text", "jsonb", "TEXT", "JSONB");
    }

    @Test
    void anotherTenantSeesNoneOfTheJobsOrTheLedger() {
        UUID job = probeJob(ORG_A, items(1, 2));
        runAll();
        assertThat(extraction.calls(null, null, 0, 50).getContent()).isNotEmpty();

        TenantContext.setCurrentOrgId(ORG_B);

        assertThatThrownBy(() -> extraction.jobs(packageId, 0, 50)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> extraction.retryFailed(job)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> extraction.calls(packageId, null, 0, 50)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(extraction.calls(null, null, 0, 50).getContent()).isEmpty();
        assertThat(retry.execute("test.read", () -> jobs.findByIdScoped(job))).isEmpty();
        assertThatThrownBy(() -> classification.list(packageId, 0, 50)).isInstanceOf(ResourceNotFoundException.class);
    }

    // --------------------------------------------------------------------------------------------
    // The LLM switch and the budget
    // --------------------------------------------------------------------------------------------

    @Test
    void withLlmReadingTurnedOffAJobWaitsAndResumesWhenItIsTurnedBackOn() {
        readingOff(ORG_A);
        UUID job = probeJob(ORG_A, items(1, 3));

        runAll();

        assertThat(status(job)).isEqualTo(ExtractJobStatus.WAITING_LLM);
        assertThat(job(job).getMessage()).isEqualTo(LlmGate.READING_OFF);
        assertThat(candidateCount(ORG_A)).as("waiting is never reported as nothing found").isZero();
        verify(client, never()).complete(any());

        jdbc.update("UPDATE doc_ingest_org_settings SET llm_reading_enabled = true WHERE organization_id = ?", ORG_A);
        runAll();

        assertThat(status(job)).isEqualTo(ExtractJobStatus.DONE);
        assertThat(candidateCount(ORG_A)).isEqualTo(3);
    }

    @Test
    void withTheEndpointSwitchedOffAJobWaitsAsWell() {
        when(client.isAvailable()).thenReturn(false);
        UUID job = probeJob(ORG_A, items(1, 3));

        runAll();

        assertThat(status(job)).isEqualTo(ExtractJobStatus.WAITING_LLM);
        assertThat(job(job).getMessage()).isEqualTo(LlmGate.NOT_CONFIGURED);
        when(client.isAvailable()).thenReturn(true);
        runAll();
        assertThat(status(job)).isEqualTo(ExtractJobStatus.DONE);
    }

    @Test
    void overBudgetAJobPausesWithItsWorkKeptAndResumesWhenAnAdminRaisesTheLimit() {
        jdbc.update("UPDATE doc_ingest_package SET llm_budget_tokens = 100 WHERE id = ?", packageId);
        UUID job = probeJob(ORG_A, items(1, 16));
        properties.setCallsPerJob(1);

        runAll();

        assertThat(status(job)).isEqualTo(ExtractJobStatus.PAUSED_BUDGET);
        assertThat(candidateCount(ORG_A)).as("the chunk read before the limit is kept").isEqualTo(8);
        assertThat(extraction.spend(packageId).exceeded()).isNotNull();

        extraction.setPackageBudget(packageId, new LlmBudgetRequest(null, 1_000_000L));
        runAll();

        assertThat(status(job)).isEqualTo(ExtractJobStatus.DONE);
        assertThat(candidateCount(ORG_A)).isEqualTo(16);
        assertThat(ledgerRows(job)).as("the chunk read before the pause is not sent again")
                .containsExactly("1-8#1 OK", "9-16#1 OK");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM doc_ingest_setting_change WHERE organization_id = ? "
                + "AND setting = 'PACKAGE_BUDGET_TOKENS'", Long.class, ORG_A)).isEqualTo(1);
    }

    // --------------------------------------------------------------------------------------------
    // The queue: claim, lease, allow-list
    // --------------------------------------------------------------------------------------------

    @Test
    void ofTwoReplicasClaimingTheSameJobExactlyOneGetsIt() {
        UUID job = probeJob(ORG_A, items(1, 1));

        assertThat(dispatcher.claim(job)).isTrue();
        assertThat(dispatcher.claim(job)).isFalse();
        assertThat(job(job).getAttempt()).isEqualTo(1);
    }

    @Test
    void aJobWhoseReplicaDiedIsRecoveredByTheNextPollAndTheOldRunCannotWrite() {
        UUID job = probeJob(ORG_A, items(1, 2));
        assertThat(dispatcher.claim(job)).isTrue();
        jdbc.update("UPDATE doc_ingest_extract_job SET lease_expires_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(1), job);

        dispatcher.recoverExpiredLeases();

        assertThat(status(job)).isEqualTo(ExtractJobStatus.QUEUED);
        assertThat(dispatcher.claim(job)).isTrue();
        LocalDateTime now = LocalDateTime.now();
        assertThat(retry.execute("test.lease", () -> jobs.moveLease(job, ORG_A, dispatcher.node(), 1, 0, now.plusMinutes(5), now)))
                .as("the first run's claim, attempt 1, no longer moves the lease").isZero();
        jdbc.update("UPDATE doc_ingest_extract_job SET status = 'QUEUED' WHERE id = ?", job);
        runAll();
        assertThat(status(job)).isEqualTo(ExtractJobStatus.DONE);
    }

    @Test
    void anOrganizationTakenOffTheAllowListHasItsQueuedJobsClosed() {
        UUID pkg = pkg(ORG_OFF_LIST);
        UUID doc = pdf(ORG_OFF_LIST, pkg, "x.pdf", item(1));
        UUID job = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_ingest_extract_job (id, organization_id, package_id, document_id, extractor_key, "
                + "extractor_version, status, queued_at, created_at, updated_at) VALUES (?, ?, ?, ?, 'classify', '1', "
                + "'WAITING_LLM', now(), now(), now())", job, ORG_OFF_LIST, pkg, doc);

        dispatcher.poll(DIRECT, DIRECT);

        assertThat(jdbc.queryForObject("SELECT status FROM doc_ingest_extract_job WHERE id = ?", String.class, job))
                .isEqualTo("FAILED");
        verify(client, never()).complete(any());
    }

    // --------------------------------------------------------------------------------------------
    // Fixtures
    // --------------------------------------------------------------------------------------------

    /** Polls until no job is queued, as replicas would. */
    void runAll() {
        for (int i = 0; i < 10; i++) {
            dispatcher.poll(DIRECT, DIRECT);
            if (jdbc.queryForObject("SELECT COUNT(*) FROM doc_ingest_extract_job WHERE status IN ('QUEUED', 'RUNNING') "
                    + "AND organization_id IN (?, ?)", Long.class, ORG_A, ORG_B) == 0) {
                return;
            }
        }
    }

    ChatCompletionResult itemsAnswer(ChatCompletionRequest req) {
        StringBuilder sb = new StringBuilder("{\"values\": [");
        List<Integer> pages = pagesOf(req);
        for (int i = 0; i < pages.size(); i++) {
            int p = pages.get(i);
            sb.append(i == 0 ? "" : ",").append("{\"key\": \"k").append(p).append("\", \"name\": \"Item ").append(p)
                    .append("\", \"amount\": \"1,250.00\", \"page\": ").append(p).append(", \"quote\": \"Item ").append(p)
                    .append(" costs 1,250.00\"}");
        }
        return ok(sb.append("]}").toString());
    }

    static List<Integer> pagesOf(ChatCompletionRequest req) {
        Matcher m = PAGES.matcher(req.userPrompt());
        if (!m.find()) {
            return List.of();
        }
        return java.util.Arrays.stream(m.group(1).split(",")).map(Integer::valueOf).toList();
    }

    static ChatCompletionResult ok(String content) {
        return new ChatCompletionResult(ChatCompletionOutcome.OK, 200, content, "stop", 100, 20, 120, "test-model",
                Duration.ofMillis(7), null, "{}");
    }

    static ChatCompletionResult truncated() {
        return new ChatCompletionResult(ChatCompletionOutcome.TRUNCATED, 200, "{\"values\": [", "length", 100, 1500, 1600,
                "test-model", Duration.ofMillis(7), "cut short", "{}");
    }

    static ChatCompletionResult failed(ChatCompletionOutcome outcome, int status) {
        return new ChatCompletionResult(outcome, status, null, null, null, null, null, null, Duration.ofMillis(3),
                "failed", null);
    }

    UUID pkg(long org) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_ingest_package (id, organization_id, name, stage, status, created_at, updated_at) "
                + "VALUES (?, ?, 'Tender', 'TENDER', 'OPEN', now(), now())", id, org);
        return id;
    }

    String prefix(long org, UUID pkg, UUID doc) {
        return DocumentIngestionStorageLayout.outputPrefix(org, pkg, doc);
    }

    UUID pdf(long org, UUID pkg, String name, String... pages) {
        return pdf(org, pkg, name, List.of(pages));
    }

    UUID pdf(long org, UUID pkg, String name, List<String> pages) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_ingest_document (id, organization_id, package_id, source_key, output_prefix, sha256, "
                        + "file_name, file_type, size_bytes, status, page_count, created_at, updated_at) VALUES (?, ?, ?, 'k', ?, ?, ?, "
                        + "'PDF', 1, 'PARSED', ?, now(), now())", id, org, pkg, prefix(org, pkg, id), "b".repeat(64), name,
                pages.size());
        objects.put(prefix(org, pkg, id) + "meta.json", ("{\"fileType\": \"PDF\", \"pageCount\": " + pages.size()
                + ", \"warnings\": [], \"newKey\": 1}").getBytes(StandardCharsets.UTF_8));
        objects.put(prefix(org, pkg, id) + "pages.jsonl", String.join("\n", pages).getBytes(StandardCharsets.UTF_8));
        return id;
    }

    UUID workbook(long org, UUID pkg, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_ingest_document (id, organization_id, package_id, source_key, output_prefix, sha256, "
                + "file_name, file_type, size_bytes, status, created_at, updated_at) VALUES (?, ?, ?, 'k', ?, ?, ?, 'XLSX', 1, "
                + "'PARSED', now(), now())", id, org, pkg, prefix(org, pkg, id), "c".repeat(64), name);
        objects.put(prefix(org, pkg, id) + "meta.json", "{\"fileType\": \"XLSX\", \"sheets\": [{\"index\": 0, \"name\": \"Sheet1\"}]}"
                .getBytes(StandardCharsets.UTF_8));
        return id;
    }

    /** A page in the contract's shape, with a box per line and keys a reader does not know. */
    static String page(int n, String... lines) {
        StringBuilder sb = new StringBuilder("{\"page\": ").append(n).append(", \"method\": \"TEXT\", \"width\": 595.0, "
                + "\"height\": 842.0, \"lines\": [");
        for (int i = 0; i < lines.length; i++) {
            double top = 0.2 + i * 0.1;
            sb.append(i == 0 ? "" : ",").append("{\"text\": \"").append(lines[i]).append("\", \"box\": [0.1, ")
                    .append(top).append(", 0.9, ").append(top + 0.05).append("], \"ocrConfidence\": null}");
        }
        return sb.append("], \"status\": \"OK\", \"reason\": null, \"script\": \"LATIN\", \"futureKey\": true}").toString();
    }

    static String pageWithScript(int n, String script, String text) {
        return "{\"page\": " + n + ", \"method\": \"TEXT\", \"lines\": [{\"text\": \"" + text + "\", \"box\": [0.1, 0.2, 0.9, 0.25]}], "
                + "\"status\": \"OK\", \"reason\": null, \"script\": \"" + script + "\"}";
    }

    static String item(int n) {
        return page(n, "Item " + n + " costs 1,250.00");
    }

    static List<String> items(int from, int to) {
        List<String> out = new ArrayList<>();
        for (int n = from; n <= to; n++) {
            out.add(item(n));
        }
        return out;
    }

    /** A PDF whose whole range is a confirmed GCC segment, with the probe queued on it. */
    UUID probeJob(long org, List<String> pages) {
        UUID doc = pdf(org, packageId, "GCC.pdf", pages);
        UUID segment = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_ingest_segment (id, organization_id, document_id, doc_class, page_from, page_to, "
                + "status, confirmed_at, created_at, updated_at) VALUES (?, ?, ?, 'GCC', 1, ?, 'CONFIRMED', now(), now(), now())",
                segment, org, doc, pages.size());
        return retry.execute("test.queue", () -> queue.queue(tenantEntityHelper.resolveCurrentOrganization(), packageId,
                doc, segment, probe, null).getId());
    }

    UUID queueClassify(long org, UUID doc) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO doc_ingest_extract_job (id, organization_id, package_id, document_id, extractor_key, "
                + "extractor_version, status, queued_at, created_at, updated_at) VALUES (?, ?, (SELECT package_id FROM "
                + "doc_ingest_document WHERE id = ?), ?, 'classify', '1', 'QUEUED', now(), now(), now())", id, org, doc, doc);
        return id;
    }

    void readingOff(long org) {
        jdbc.update("INSERT INTO doc_ingest_org_settings (id, organization_id, llm_reading_enabled, created_at, updated_at) "
                + "VALUES (gen_random_uuid(), ?, false, now(), now())", org);
    }

    ExtractJob job(UUID id) {
        return retry.execute("test.job", () -> jobs.findByIdScoped(id).orElseThrow());
    }

    ExtractJobStatus status(UUID id) {
        return ExtractJobStatus.valueOf(jdbc.queryForObject("SELECT status FROM doc_ingest_extract_job WHERE id = ?",
                String.class, id));
    }

    List<String> ledgerRows(UUID job) {
        return jdbc.queryForList("SELECT chunk_key || '#' || call_number::STRING || ' ' || outcome FROM doc_ingest_llm_call "
                + "WHERE job_id = ? ORDER BY called_at, chunk_key, call_number", String.class, job);
    }

    long candidateCount(long org) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM doc_ingest_candidate WHERE organization_id = ?", Long.class, org);
    }

    List<CandidateDto> candidates() {
        return review.list(packageId, null, null, null, 0, 200).getContent();
    }
}
