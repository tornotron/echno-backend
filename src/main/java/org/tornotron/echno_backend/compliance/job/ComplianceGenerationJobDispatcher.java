package org.tornotron.echno_backend.compliance.job;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.common.multitenancy.WithoutTenant;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Finds queued compliance generation work and takes it. What happens to a job once taken is
 * {@link ComplianceGenerationJobWorker}'s business.
 *
 * <h2>Every replica polls, and the database decides</h2>
 *
 * <p>There is no leader and no broker. Each replica asks every couple of seconds for the
 * oldest queued jobs and tries to take one with a conditional update
 * ({@code SET status='RUNNING' WHERE id=? AND status='QUEUED'}). Exactly one replica's
 * statement can move a given row out of {@code QUEUED}, so exactly one gets it and the
 * others get a row count of zero and move on. Two replicas reading the same shortlist is
 * expected, not a bug to design around.
 *
 * <p>That is deliberately the least machinery that works. CockroachDB has no advisory locks,
 * {@code SKIP LOCKED} would be a heavier tool for the same job, and a leader election would
 * add a failure mode this design cannot have, namely a replica that believes it is the leader
 * and is not. A queue broker was rejected for a plainer reason: Redis is optional here and off
 * by default, so a queue that only worked on the Redis profile would become a second
 * configuration axis for every environment.
 *
 * <h2>What happens when a replica dies mid-run</h2>
 *
 * <p>The claim carries a lease, which the worker pushes forward as it finishes each batch. A
 * replica that is killed stops pushing. Once the lease is in the past, the next poll on any
 * replica returns the row to {@code QUEUED}, or fails it outright if it has no attempts left.
 * Re-running is safe because generation skips the compliances that already exist, so the
 * recovered attempt does the part the dead one had not got to.
 *
 * <p>The attempt is counted by the claim rather than by the worker, so a job that reliably
 * kills its worker before the worker can write anything still exhausts its attempts and stops,
 * instead of cycling for ever.
 *
 * <h2>Tenancy</h2>
 *
 * <p>The poller cannot have a tenant, since its whole purpose is to find out which tenant to
 * become. So its queries are native and read scalars only, never entities, and the moment a
 * job is claimed it is handed to the worker, which establishes the tenant through
 * {@link TenantScopedJobRunner} before touching anything.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "compliance.job.enabled", havingValue = "true", matchIfMissing = true)
public class ComplianceGenerationJobDispatcher {

    private final ComplianceGenerationJobRepository jobRepository;
    private final ComplianceGenerationJobWorker worker;
    private final TransactionRetryTemplate retryTemplate;
    private final ComplianceJobProperties properties;

    /** This replica's name on the rows it claims. Diagnostic, and the guard on stale writes. */
    private final String nodeId;

    /**
     * Its own pool rather than the shared {@code applicationTaskExecutor}. A generation run
     * occupies its thread for minutes at a time, so putting these on the executor that also
     * serves the ordinary short {@code @Async} work would let a queue of compliance runs delay
     * everything else in the application.
     */
    private final ExecutorService workers;

    /** Free worker slots. Held from before the claim until the run ends, so no claim lacks one. */
    private final Semaphore freeSlots;

    public ComplianceGenerationJobDispatcher(ComplianceGenerationJobRepository jobRepository,
                                             ComplianceGenerationJobWorker worker,
                                             TransactionRetryTemplate retryTemplate,
                                             ComplianceJobProperties properties) {
        this.jobRepository = jobRepository;
        this.worker = worker;
        this.retryTemplate = retryTemplate;
        this.properties = properties;
        this.nodeId = resolveNodeId();

        int slots = Math.max(1, properties.getMaxInFlight());
        this.freeSlots = new Semaphore(slots);
        AtomicInteger seq = new AtomicInteger();
        this.workers = Executors.newFixedThreadPool(slots, runnable -> {
            Thread thread = new Thread(runnable, "compliance-job-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        log.info("Compliance generation dispatcher active as '{}' with {} worker slot(s)", nodeId, slots);
    }

    @PreDestroy
    void shutdown() {
        // Not awaited. A run in flight loses its lease and is picked up again, which is the
        // recovery path that has to work anyway, and waiting minutes for a model call to finish
        // would turn every deployment into a slow one.
        workers.shutdownNow();
    }

    /**
     * One pass: recover jobs whose worker is gone, then take as much new work as there are free
     * slots for.
     *
     * <p>Everything here is short and non-blocking, because it runs on the application's single
     * shared scheduler thread and must not hold it. The claim is a one-row update; the work
     * itself goes to the worker pool.
     */
    @Scheduled(fixedDelayString = "${compliance.job.poll-interval-millis:2000}")
    @WithoutTenant("The queue poller belongs to no organization: it exists to find out which "
            + "organization to become, reads scalars only, and hands the claimed job to the "
            + "worker, which pins the tenant before any entity is touched")
    public void poll() {
        poll(workers);
    }

    /**
     * The pass itself, with the executor named rather than assumed.
     *
     * <p>Package-private and parameterised so a test can run a pass on the calling thread and
     * assert on what it did, instead of racing a thread pool. Nothing in production calls it
     * with anything but the pool above.
     */
    void poll(Executor executor) {
        try {
            recoverExpiredLeases();
            takeWork(executor);
        } catch (Exception e) {
            // Never let a bad pass kill the schedule: Spring stops rescheduling a @Scheduled
            // method that throws, and a queue that silently stops draining is exactly the
            // failure this whole change exists to remove.
            log.error("Compliance generation poll failed: {}", e.getMessage(), e);
        }
    }

    private void recoverExpiredLeases() {
        LocalDateTime now = LocalDateTime.now();
        int requeued = retryTemplate.execute("ComplianceGenerationJobDispatcher.requeue", () ->
                jobRepository.requeueExpiredLeases(
                        "The worker running this job stopped responding, so it was queued again.", now));
        if (requeued > 0) {
            log.warn("Returned {} compliance generation job(s) to the queue after their lease expired",
                    requeued);
        }

        int failed = retryTemplate.execute("ComplianceGenerationJobDispatcher.failExpired", () ->
                jobRepository.failExpiredLeasesOutOfAttempts(
                        "The worker running this job stopped responding and no attempts were left, "
                                + "so nothing was generated. Try again.", now));
        if (failed > 0) {
            log.error("Gave up on {} compliance generation job(s) whose worker stopped responding", failed);
        }
    }

    private void takeWork(Executor executor) {
        int slots = freeSlots.availablePermits();
        if (slots == 0) {
            return;
        }

        List<UUID> queued = retryTemplate.execute("ComplianceGenerationJobDispatcher.findQueued",
                () -> jobRepository.findQueuedIds(slots));
        for (UUID jobId : queued) {
            if (!freeSlots.tryAcquire()) {
                return;
            }
            boolean handedOff = false;
            try {
                if (claim(jobId)) {
                    executor.execute(() -> runAndRelease(jobId));
                    handedOff = true;
                }
            } finally {
                if (!handedOff) {
                    freeSlots.release();
                }
            }
        }
    }

    private boolean claim(UUID jobId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseUntil = now.plus(properties.getLeaseDuration());
        int claimed = retryTemplate.execute("ComplianceGenerationJobDispatcher.claim",
                () -> jobRepository.claim(jobId, nodeId, leaseUntil, now));
        return claimed == 1;
    }

    /**
     * Runs a claimed job and gives its slot back. The worker records every ending on the row
     * itself, so anything thrown here is a fault in the recording rather than in the run, and
     * has nowhere to go but the log.
     */
    private void runAndRelease(UUID jobId) {
        try {
            worker.run(jobId, nodeId);
        } catch (Exception e) {
            log.error("Compliance generation job {} could not be run: {}", jobId, e.getMessage(), e);
        } finally {
            freeSlots.release();
        }
    }

    /** This replica's name on the rows it claims. */
    String nodeId() {
        return nodeId;
    }

    /**
     * A name for this replica. The hostname where the platform gives one, since that is what
     * someone reading the row will want, and a random suffix regardless, because containers
     * routinely report a hostname their neighbours also report and two replicas must hold
     * distinguishable claims.
     */
    private static String resolveNodeId() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            host = "unknown";
        }
        String id = host + "-" + UUID.randomUUID().toString().substring(0, 8);
        return id.length() <= 120 ? id : id.substring(id.length() - 120);
    }
}
