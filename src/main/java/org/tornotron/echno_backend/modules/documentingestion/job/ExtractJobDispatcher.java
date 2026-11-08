package org.tornotron.echno_backend.modules.documentingestion.job;

import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.common.multitenancy.WithoutTenant;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionModuleEnabled;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionSwitch;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractJobStatus;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.IngestLlmProperties;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.LlmBudget;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.LlmGate;
import org.tornotron.echno_backend.modules.documentingestion.repository.ExtractJobRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.ExtractJobRepository.WaitingRef;

/**
 * The backend's extract queue, on the compliance pattern (design section 6.4): every replica polls, one
 * conditional update claims, the lease moves with each chunk, and a job whose replica died is returned
 * to the queue by the next poll on any replica, or failed once it has no attempts left. Each pass also
 * closes queued work of an organization taken off the allow-list, and puts back in the queue the jobs
 * whose wait has cleared: LLM reading on again, or the budget raised or a new month begun.
 *
 * <p>Exists only with the operator switch on. With it off, rows wait where they are and resume when it
 * returns.
 */
@Slf4j
@Component
@DocumentIngestionModuleEnabled
public class ExtractJobDispatcher {

    static final int WAITING_BATCH = 50;
    static final String LEASE_REQUEUED = "The replica reading this job stopped responding, so it was queued again.";
    static final String LEASE_GIVE_UP = "The replica reading this job stopped responding three times. Use "
            + "\"retry failed parts\" to try again.";
    static final String REMOVED_FROM_ALLOW_LIST = "Document import was turned off for this organization before "
            + "the reading ran.";

    private final ExtractJobRepository jobs;
    private final ExtractJobWorker worker;
    private final LlmGate gate;
    private final LlmBudget budget;
    private final TenantScopedJobRunner tenantRunner;
    private final TransactionRetryTemplate retry;
    private final IngestLlmProperties properties;
    private final DocumentIngestionSwitch operatorSwitch;
    private final String nodeId = nodeId();

    /** Job threads: one per job in flight. A job's own calls run on the call pool. */
    private final ExecutorService jobPool;
    private final ExecutorService callPool;
    private final Semaphore freeSlots;

    public ExtractJobDispatcher(ExtractJobRepository jobs, ExtractJobWorker worker, LlmGate gate, LlmBudget budget,
                                TenantScopedJobRunner tenantRunner, TransactionRetryTemplate retry,
                                IngestLlmProperties properties, Environment environment) {
        this.jobs = jobs;
        this.worker = worker;
        this.gate = gate;
        this.budget = budget;
        this.tenantRunner = tenantRunner;
        this.retry = retry;
        this.properties = properties;
        this.operatorSwitch = new DocumentIngestionSwitch(environment);
        int slots = Math.max(1, properties.getMaxInFlight());
        this.freeSlots = new Semaphore(slots);
        this.jobPool = Executors.newFixedThreadPool(slots, daemon("doc-ingest-extract-"));
        this.callPool = Executors.newFixedThreadPool(slots * Math.max(1, properties.getCallsPerJob()),
                daemon("doc-ingest-call-"));
        log.info("Document ingestion extract queue active as '{}' with {} job slot(s)", nodeId, slots);
    }

    @PreDestroy
    void shutdown() {
        // Not awaited: a run in flight loses its lease and is recovered, which has to work anyway.
        jobPool.shutdownNow();
        callPool.shutdownNow();
    }

    @Scheduled(fixedDelayString = "${echno.modules.document-ingestion.llm.poll-interval-millis:5000}",
            initialDelayString = "${echno.modules.document-ingestion.llm.initial-delay-millis:5000}")
    @WithoutTenant("The extract queue poller belongs to no organization: it reads job ids and organizations as "
            + "scalars and pins each job's tenant before any entity is touched")
    public void poll() {
        poll(jobPool, callPool);
    }

    /** One pass, with the executors named, so a test can run it on the calling thread. */
    void poll(Executor jobExecutor, Executor callExecutor) {
        try {
            recoverExpiredLeases();
            closeOutsideTheAllowList();
            resumeWaiting();
            takeWork(jobExecutor, callExecutor);
        } catch (Exception e) {
            // A thrown @Scheduled method is never rescheduled; log and try again next pass.
            log.error("Document ingestion extract poll failed: {}", e.getMessage(), e);
        }
    }

    void recoverExpiredLeases() {
        LocalDateTime now = LocalDateTime.now();
        int requeued = retry.execute("DocumentIngestion.extractRequeueExpired",
                () -> jobs.requeueExpiredLeases(LEASE_REQUEUED, now));
        if (requeued > 0) {
            log.warn("Returned {} extract job(s) to the queue after their lease expired", requeued);
        }
        int failed = retry.execute("DocumentIngestion.extractFailExpired",
                () -> jobs.failExpiredLeases(LEASE_GIVE_UP, now));
        if (failed > 0) {
            log.error("Gave up on {} extract job(s) whose replica stopped responding", failed);
        }
    }

    void closeOutsideTheAllowList() {
        List<Long> organizations = retry.execute("DocumentIngestion.extractQueuedOrgs",
                jobs::findOrganizationsWithQueuedWork);
        for (Long organizationId : organizations) {
            if (!operatorSwitch.allows(organizationId)) {
                int closed = retry.execute("DocumentIngestion.extractCloseOrg",
                        () -> jobs.failQueuedForOrganization(organizationId, REMOVED_FROM_ALLOW_LIST, LocalDateTime.now()));
                log.warn("Closed {} queued extract job(s) of organization {}, which is not on the allow-list", closed,
                        organizationId);
            }
        }
    }

    /** Puts back in the queue the waiting jobs whose reason has cleared. The checks run in each job's tenant. */
    void resumeWaiting() {
        List<WaitingRef> waiting = retry.execute("DocumentIngestion.extractWaiting",
                () -> jobs.findWaiting(WAITING_BATCH));
        for (WaitingRef ref : waiting) {
            if (!operatorSwitch.allows(ref.getOrganizationId())) {
                continue;
            }
            boolean cleared = tenantRunner.callForTenant(ref.getOrganizationId(), () -> isCleared(ref));
            if (cleared) {
                int resumed = retry.execute("DocumentIngestion.extractResume", () -> jobs.resume(ref.getId(),
                        ref.getOrganizationId(), ref.getStatus(), LocalDateTime.now()));
                if (resumed == 1) {
                    log.info("Extract job {} resumes: its {} cleared", ref.getId(), ref.getStatus());
                }
            }
        }
    }

    private boolean isCleared(WaitingRef ref) {
        if (ExtractJobStatus.WAITING_LLM.name().equals(ref.getStatus())) {
            return gate.blocked(ref.getOrganizationId()).isEmpty();
        }
        return budget.exceeded(ref.getOrganizationId(), ref.getPackageId()).isEmpty();
    }

    private void takeWork(Executor jobExecutor, Executor callExecutor) {
        int slots = freeSlots.availablePermits();
        if (slots == 0) {
            return;
        }
        List<UUID> queued = retry.execute("DocumentIngestion.extractQueued", () -> jobs.findQueuedIds(slots));
        for (UUID jobId : queued) {
            if (!freeSlots.tryAcquire()) {
                return;
            }
            boolean handedOff = false;
            try {
                if (claim(jobId)) {
                    jobExecutor.execute(() -> {
                        try {
                            worker.run(jobId, nodeId, callExecutor);
                        } catch (Exception e) {
                            log.error("Extract job {} could not be run: {}", jobId, e.getMessage(), e);
                        } finally {
                            freeSlots.release();
                        }
                    });
                    handedOff = true;
                }
            } finally {
                if (!handedOff) {
                    freeSlots.release();
                }
            }
        }
    }

    boolean claim(UUID jobId) {
        LocalDateTime now = LocalDateTime.now();
        return retry.execute("DocumentIngestion.extractClaim",
                () -> jobs.claim(jobId, nodeId, now.plus(properties.getLeaseDuration()), now)) == 1;
    }

    String node() {
        return nodeId;
    }

    private static java.util.concurrent.ThreadFactory daemon(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static String nodeId() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            host = "unknown";
        }
        String id = host + "-" + UUID.randomUUID().toString().substring(0, 8);
        return id.length() <= 120 ? id : id.substring(id.length() - 120);
    }
}
