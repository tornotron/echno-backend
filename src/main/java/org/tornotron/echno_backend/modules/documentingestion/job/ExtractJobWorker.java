package org.tornotron.echno_backend.modules.documentingestion.job;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.common.multitenancy.WithoutTenant;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionModuleEnabled;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionSwitch;
import org.tornotron.echno_backend.modules.documentingestion.domain.DocumentFileType;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractJob;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractJobStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestDocument;
import org.tornotron.echno_backend.modules.documentingestion.domain.LlmCallOutcome;
import org.tornotron.echno_backend.modules.documentingestion.domain.Segment;
import org.tornotron.echno_backend.modules.documentingestion.domain.SegmentStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.UnreadPage;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkPages;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkReader;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractCompletion;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractContext;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractPlan;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.Extractor;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ExtractorRegistry;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.IngestLlmProperties;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.LlmBudget;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.LlmGate;
import org.tornotron.echno_backend.modules.documentingestion.parsing.PageText;
import org.tornotron.echno_backend.modules.documentingestion.parsing.ParsedOutputReader;
import org.tornotron.echno_backend.modules.documentingestion.parsing.TableRow;
import org.tornotron.echno_backend.modules.documentingestion.parsing.WorkbookMeta;
import org.tornotron.echno_backend.modules.documentingestion.repository.ExtractJobRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestDocumentRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.LlmCallRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.SegmentRepository;

/**
 * Runs one claimed extract job to an ending and writes down which: {@code DONE}, {@code PARTIAL} with
 * the unread pages named, {@code FAILED}, or a wait ({@code WAITING_LLM}, {@code PAUSED_BUDGET}) that
 * keeps what is done and resumes by itself. Nothing is ever reported as "nothing found" because a call
 * could not be made.
 *
 * <p>Chunks go to the call pool, at most {@code calls-per-job} at a time, each under the job's tenant.
 * The lease moves after every chunk. Every write is fenced on this run's claim (replica and attempt),
 * so a run whose lease lapsed and whose job was taken over leaves the new owner alone; the chunks it
 * did read are in the ledger and are skipped by the new owner.
 */
@Slf4j
@Component
@DocumentIngestionModuleEnabled
public class ExtractJobWorker {

    static final String REMOVED_FROM_ALLOW_LIST = "Document import was turned off for this organization before "
            + "the reading finished.";
    static final String EXTRACTOR_OFF = "This reader is switched off on this server, so the job was not run.";

    private final ExtractJobRepository jobs;
    private final IngestDocumentRepository documents;
    private final SegmentRepository segments;
    private final LlmCallRepository ledger;
    private final ExtractorRegistry registry;
    private final ChunkReader reader;
    private final ParsedOutputReader outputs;
    private final LlmGate gate;
    private final LlmBudget budget;
    private final IngestLlmProperties properties;
    private final TenantScopedJobRunner tenantRunner;
    private final TransactionRetryTemplate retry;
    private final DocumentIngestionSwitch operatorSwitch;

    public ExtractJobWorker(ExtractJobRepository jobs, IngestDocumentRepository documents, SegmentRepository segments,
                            LlmCallRepository ledger, ExtractorRegistry registry, ChunkReader reader,
                            ParsedOutputReader outputs, LlmGate gate, LlmBudget budget, IngestLlmProperties properties,
                            TenantScopedJobRunner tenantRunner, TransactionRetryTemplate retry, Environment environment) {
        this.jobs = jobs;
        this.documents = documents;
        this.segments = segments;
        this.ledger = ledger;
        this.registry = registry;
        this.reader = reader;
        this.outputs = outputs;
        this.gate = gate;
        this.budget = budget;
        this.properties = properties;
        this.tenantRunner = tenantRunner;
        this.retry = retry;
        this.operatorSwitch = new DocumentIngestionSwitch(environment);
    }

    /** The claim this run holds. */
    record Claim(UUID jobId, Long organizationId, String node, int attempt, int maxAttempts) {
    }

    /** What a run reads about its job before it starts, as plain values. */
    record Loaded(ExtractContext context, String extractorKey, String outputPrefix) {
    }

    /** Runs a job the caller has claimed for {@code node}. Throws nothing: every ending is on the row. */
    @WithoutTenant("Reading which organization a claimed job belongs to is the step that establishes the tenant")
    public void run(UUID jobId, String node, Executor calls) {
        Optional<ExtractJobRepository.DispatchRow> found = retry.execute("DocumentIngestion.extractDispatchRow",
                () -> jobs.findDispatchRow(jobId));
        if (found.isEmpty()) {
            log.warn("Extract job {} vanished between being claimed and being run", jobId);
            return;
        }
        Claim claim = new Claim(jobId, found.get().getOrganizationId(), node, found.get().getAttempt(),
                found.get().getMaxAttempts());
        if (!operatorSwitch.allows(claim.organizationId())) {
            tenantRunner.runForTenant(claim.organizationId(), () -> end(claim, ExtractJobStatus.FAILED,
                    REMOVED_FROM_ALLOW_LIST, null));
            return;
        }
        tenantRunner.runForTenant(claim.organizationId(), () -> {
            try {
                runClaimed(claim, calls);
            } catch (Exception e) {
                recordFailure(claim, e);
            }
        });
    }

    private void runClaimed(Claim claim, Executor calls) {
        Loaded loaded = retry.execute("DocumentIngestion.extractLoad", () -> load(claim));
        Extractor extractor = registry.find(loaded.extractorKey()).orElse(null);
        if (extractor == null) {
            end(claim, ExtractJobStatus.FAILED, EXTRACTOR_OFF, null);
            return;
        }
        ExtractContext context = withOutputs(loaded, extractor.readsTables());
        markSegment(context.segmentId(), SegmentStatus.EXTRACTING, null);

        ExtractPlan plan = extractor.plan(context);
        Map<String, Object> merged = retry.execute("DocumentIngestion.extractPlan", () -> {
            ExtractJob job = jobs.findByIdScoped(claim.jobId()).orElseThrow();
            Map<String, Object> result = new LinkedHashMap<>(job.getResult() == null ? Map.of() : job.getResult());
            result.putAll(plan.result());
            job.setResult(result);
            job.setUnreadPages(new ArrayList<>(plan.unread()));
            jobs.save(job);
            return result;
        });
        context = context.withResult(merged);

        if (plan.toSend().isEmpty()) {
            complete(claim, extractor, context, plan.unread(), List.of(), 0, 0, false);
            return;
        }
        List<ChunkPages> chunks = ChunkPages.of(plan.toSend(), properties.getChunkPages());
        retry.executeWithoutResult("DocumentIngestion.extractChunks", () -> jobs.findByIdScoped(claim.jobId())
                .ifPresent(job -> {
                    job.setChunksTotal(chunks.size());
                    jobs.save(job);
                }));
        Optional<String> blocked = gate.blocked(claim.organizationId());
        if (blocked.isPresent()) {
            end(claim, ExtractJobStatus.WAITING_LLM, blocked.get(), null);
            return;
        }

        ChunkReader.Known known = retry.execute("DocumentIngestion.extractKnown", () -> new ChunkReader.Known(
                new HashSet<>(ledger.findChunkKeysWithOutcome(claim.organizationId(), claim.jobId(), LlmCallOutcome.OK)),
                new HashSet<>(ledger.findChunkKeysWithOutcome(claim.organizationId(), claim.jobId(),
                        LlmCallOutcome.TRUNCATED))));
        ChunkReader.Outcome total = readAll(claim, extractor, context, chunks, known, calls);

        if (total.stop() != null) {
            switch (total.stop().stop()) {
                case LEASE_LOST -> log.warn("Extract job {} was taken over by another run; this run stops", claim.jobId());
                case BUDGET -> end(claim, ExtractJobStatus.PAUSED_BUDGET, total.stop().message(), total);
                case LLM_OFF -> end(claim, ExtractJobStatus.WAITING_LLM, total.stop().message(), total);
            }
            return;
        }
        Map<String, Object> result = retry.execute("DocumentIngestion.extractResult",
                () -> jobs.findByIdScoped(claim.jobId()).map(ExtractJob::getResult).orElse(Map.of()));
        complete(claim, extractor, context.withResult(result), plan.unread(), total.failedPages(), total.chunks(),
                total.failed(), true);
    }

    /** Sends the chunks through the pool, four at a time by default, and sums how they ended. */
    private ChunkReader.Outcome readAll(Claim claim, Extractor extractor, ExtractContext context, List<ChunkPages> chunks,
                                        ChunkReader.Known known, Executor calls) {
        AtomicReference<ChunkReader.StopReason> stop = new AtomicReference<>();
        AtomicBoolean leaseLost = new AtomicBoolean();
        AtomicInteger done = new AtomicInteger();
        ChunkReader.Guard guard = () -> {
            if (stop.get() != null) {
                return Optional.of(stop.get());
            }
            if (leaseLost.get()) {
                return Optional.of(new ChunkReader.StopReason(ChunkReader.Stop.LEASE_LOST, null));
            }
            Optional<String> off = gate.blocked(claim.organizationId());
            if (off.isPresent()) {
                stop.compareAndSet(null, new ChunkReader.StopReason(ChunkReader.Stop.LLM_OFF, off.get()));
                return Optional.of(stop.get());
            }
            Optional<String> over = budget.exceeded(claim.organizationId(), context.packageId());
            if (over.isPresent()) {
                stop.compareAndSet(null, new ChunkReader.StopReason(ChunkReader.Stop.BUDGET, over.get()));
                return Optional.of(stop.get());
            }
            return Optional.empty();
        };
        Semaphore permits = new Semaphore(Math.max(1, properties.getCallsPerJob()));
        List<CompletableFuture<ChunkReader.Outcome>> running = new ArrayList<>();
        for (ChunkPages chunk : chunks) {
            if (stop.get() != null || leaseLost.get()) {
                break;
            }
            permits.acquireUninterruptibly();
            running.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return tenantRunner.callForTenant(claim.organizationId(),
                            () -> reader.read(context, extractor, chunk, known, guard));
                } finally {
                    permits.release();
                    if (!moveLease(claim, done.incrementAndGet())) {
                        leaseLost.set(true);
                    }
                }
            }, calls));
        }
        ChunkReader.Outcome total = new ChunkReader.Outcome(null, List.of(), List.of(), 0, 0);
        for (CompletableFuture<ChunkReader.Outcome> f : running) {
            total = plus(total, f.join());
        }
        if (leaseLost.get()) {
            return new ChunkReader.Outcome(new ChunkReader.StopReason(ChunkReader.Stop.LEASE_LOST, null),
                    total.readPages(), total.failedPages(), total.chunks(), total.failed());
        }
        if (total.stop() == null && stop.get() != null) {
            return new ChunkReader.Outcome(stop.get(), total.readPages(), total.failedPages(), total.chunks(),
                    total.failed());
        }
        return total;
    }

    private void complete(Claim claim, Extractor extractor, ExtractContext context, List<UnreadPage> notSent,
                          List<Integer> failedPages, int chunks, int failedChunks, boolean usedLlm) {
        if (!moveLease(claim, chunks)) {
            log.warn("Extract job {} was taken over before it could finish; this run stops", claim.jobId());
            return;
        }
        List<UnreadPage> failed = failedPages.stream().distinct().sorted()
                .map(p -> new UnreadPage(p, UnreadPage.READ_FAILED,
                        "No usable answer came back for this page after the retries. Use \"retry failed parts\"."))
                .toList();
        List<UnreadPage> unread = new ArrayList<>(notSent);
        unread.addAll(failed);
        extractor.complete(context, new ExtractCompletion(unread, usedLlm));
        ExtractJobStatus status;
        String message = null;
        if (failedChunks == 0) {
            status = ExtractJobStatus.DONE;
        } else if (failedChunks == chunks) {
            status = ExtractJobStatus.FAILED;
            message = "No chunk could be read after the retries. Use \"retry failed parts\" to try again.";
        } else {
            status = ExtractJobStatus.PARTIAL;
            message = "Read with " + pagesText(failedPages) + " not read. Use \"retry failed parts\" to try them again.";
        }
        ChunkReader.Outcome outcome = new ChunkReader.Outcome(null, List.of(), failedPages, chunks, failedChunks);
        if (end(claim, status, message, outcome) && context.segmentId() != null) {
            SegmentStatus segmentStatus = switch (status) {
                case DONE -> SegmentStatus.EXTRACTED;
                case PARTIAL -> SegmentStatus.PARTIAL;
                default -> SegmentStatus.FAILED;
            };
            markSegment(context.segmentId(), segmentStatus, unread);
        }
    }

    /**
     * Writes an ending if this run still holds the job.
     *
     * @return whether it did
     */
    private boolean end(Claim claim, ExtractJobStatus status, String message, ChunkReader.Outcome outcome) {
        return retry.execute("DocumentIngestion.extractEnd", () -> {
            ExtractJob job = jobs.findByIdScoped(claim.jobId()).orElse(null);
            if (job == null || !stillOurs(job, claim)) {
                return false;
            }
            job.setStatus(status);
            job.setMessage(cut(message));
            job.setClaimedBy(null);
            job.setLeaseExpiresAt(null);
            if (outcome != null) {
                job.setChunksDone(Math.max(job.getChunksDone(), outcome.chunks() - outcome.failed()));
                job.setChunksFailed(outcome.failed());
                job.setFailedPages(outcome.failedPages().stream().distinct().sorted()
                        .map(p -> new UnreadPage(p, UnreadPage.READ_FAILED, null)).toList());
            }
            if (status == ExtractJobStatus.DONE || status == ExtractJobStatus.PARTIAL
                    || status == ExtractJobStatus.FAILED) {
                job.setFinishedAt(LocalDateTime.now());
            }
            jobs.save(job);
            log.info("Extract job {} ({}) ended {}", job.getId(), job.getExtractorKey(), status);
            return true;
        });
    }

    /**
     * A run that threw. A deterministic failure (a missing output, a document that is not there) ends
     * the job; anything else goes back to the queue while attempts remain.
     */
    private void recordFailure(Claim claim, Exception failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        boolean deterministic = failure instanceof InvalidRequestException || failure instanceof ResourceNotFoundException;
        if (deterministic || claim.attempt() >= claim.maxAttempts()) {
            log.error("Extract job {} failed: {}", claim.jobId(), message, failure);
            end(claim, ExtractJobStatus.FAILED, message, null);
            return;
        }
        log.warn("Extract job {} failed on attempt {} of {} and goes back to the queue: {}", claim.jobId(),
                claim.attempt(), claim.maxAttempts(), message);
        retry.executeWithoutResult("DocumentIngestion.extractRequeue", () -> {
            ExtractJob job = jobs.findByIdScoped(claim.jobId()).orElse(null);
            if (job == null || !stillOurs(job, claim)) {
                return;
            }
            job.setStatus(ExtractJobStatus.QUEUED);
            job.setClaimedBy(null);
            job.setLeaseExpiresAt(null);
            job.setMessage(cut("Attempt " + claim.attempt() + " of " + claim.maxAttempts()
                    + " did not finish and is being tried again. " + message));
            jobs.save(job);
        });
    }

    private boolean moveLease(Claim claim, int chunksDone) {
        LocalDateTime now = LocalDateTime.now();
        return retry.execute("DocumentIngestion.extractLease", () -> jobs.moveLease(claim.jobId(),
                claim.organizationId(), claim.node(), claim.attempt(), chunksDone,
                now.plus(properties.getLeaseDuration()), now)) == 1;
    }

    private void markSegment(UUID segmentId, SegmentStatus status, List<UnreadPage> unread) {
        if (segmentId == null) {
            return;
        }
        retry.executeWithoutResult("DocumentIngestion.extractSegment", () -> segments.findByIdScoped(segmentId)
                .ifPresent(s -> {
                    s.setStatus(status);
                    if (unread != null) {
                        s.setUnreadPages(new ArrayList<>(unread));
                    }
                    segments.save(s);
                }));
    }

    private Loaded load(Claim claim) {
        ExtractJob job = jobs.findByIdScoped(claim.jobId())
                .orElseThrow(() -> new ResourceNotFoundException("No extract job " + claim.jobId()));
        IngestDocument doc = documents.findByIdScoped(job.getDocumentId())
                .orElseThrow(() -> new ResourceNotFoundException("The job's document no longer exists"));
        if (doc.getOutputPrefix() == null) {
            throw new InvalidRequestException(doc.getFileName() + " has not been read yet.");
        }
        Segment segment = job.getSegmentId() == null ? null : segments.findByIdScoped(job.getSegmentId())
                .orElseThrow(() -> new ResourceNotFoundException("The segment was redrawn after the job was queued"));
        ExtractContext context = new ExtractContext(job.getId(), claim.organizationId(), job.getPackageId(), doc.getId(),
                doc.getFileName(), doc.getFileType(), doc.getSha256(), job.getSegmentId(),
                segment == null ? null : segment.getDocClass(), segment == null ? null : segment.getPageFrom(),
                segment == null ? null : segment.getPageTo(), List.of(), null, job.getResult());
        return new Loaded(context, job.getExtractorKey(), doc.getOutputPrefix());
    }

    private ExtractContext withOutputs(Loaded loaded, boolean readsTables) {
        ExtractContext c = loaded.context();
        WorkbookMeta meta = outputs.meta(loaded.outputPrefix());
        List<PageText> pages = List.of();
        if (c.fileType() == DocumentFileType.PDF) {
            pages = outputs.pages(loaded.outputPrefix()).stream()
                    .filter(p -> c.pageFrom() == null || p.page() >= c.pageFrom())
                    .filter(p -> c.pageTo() == null || p.page() <= c.pageTo())
                    .toList();
        }
        List<TableRow> tables = readsTables ? tables(loaded.outputPrefix(), c) : List.of();
        return new ExtractContext(c.jobId(), c.organizationId(), c.packageId(), c.documentId(), c.fileName(),
                c.fileType(), c.documentSha256(), c.segmentId(), c.segmentClass(), c.pageFrom(), c.pageTo(), pages,
                meta, c.result(), tables);
    }

    /**
     * The table rows in the segment's pages, or every row of a workbook. A PDF with no table may have no
     * {@code tables.jsonl} from a worker before 0.2.0, which reads as no rows.
     */
    private List<TableRow> tables(String outputPrefix, ExtractContext c) {
        List<TableRow> rows;
        try {
            rows = outputs.tables(outputPrefix);
        } catch (RuntimeException e) {
            if (c.fileType() != DocumentFileType.PDF) {
                throw e;
            }
            log.info("Extract job {}: no table rows were read for a PDF ({})", c.jobId(), e.getMessage());
            return List.of();
        }
        return rows.stream()
                .filter(r -> r.page() == null || c.pageFrom() == null || r.page() >= c.pageFrom())
                .filter(r -> r.page() == null || c.pageTo() == null || r.page() <= c.pageTo())
                .toList();
    }

    private static boolean stillOurs(ExtractJob job, Claim claim) {
        boolean ours = job.getStatus() == ExtractJobStatus.RUNNING && claim.node().equals(job.getClaimedBy())
                && job.getAttempt() == claim.attempt();
        if (!ours) {
            log.warn("Extract job {} is no longer held by {} on attempt {}; leaving it to its current owner",
                    job.getId(), claim.node(), claim.attempt());
        }
        return ours;
    }

    private static ChunkReader.Outcome plus(ChunkReader.Outcome a, ChunkReader.Outcome b) {
        List<Integer> read = new ArrayList<>(a.readPages());
        read.addAll(b.readPages());
        List<Integer> failed = new ArrayList<>(a.failedPages());
        failed.addAll(b.failedPages());
        return new ChunkReader.Outcome(a.stop() != null ? a.stop() : b.stop(), read, failed, a.chunks() + b.chunks(),
                a.failed() + b.failed());
    }

    static String pagesText(List<Integer> pages) {
        List<Integer> sorted = new ArrayList<>(new TreeSet<>(pages));
        return (sorted.size() == 1 ? "page " : "pages ") + ChunkPages.describe(sorted);
    }

    private static String cut(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 1000 ? message : message.substring(0, 997) + "...";
    }
}
