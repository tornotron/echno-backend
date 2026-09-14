package org.tornotron.echno_backend.modules.bim.importer;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.common.multitenancy.WithoutTenant;
import org.tornotron.echno_backend.modules.bim.BimModuleEnabled;
import org.tornotron.echno_backend.modules.bim.repository.BimImportJobRepository;
import org.tornotron.echno_backend.modules.bim.repository.BimImportJobRepository.JobRef;

/**
 * The backend's side of the import contract: recovers leases the worker let expire, mirrors
 * a claim on the version, hands DONE jobs to the pipeline and closes FAILED ones on their
 * version. Reads scalars across tenants, then pins the job's tenant before any entity is
 * touched, the same shape as the compliance dispatcher.
 */
@Component
@BimModuleEnabled
@ConditionalOnProperty(name = BimImportPoller.PROPERTY, havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class BimImportPoller {

    public static final String PROPERTY = "echno.modules.bim.ingest.enabled";
    public static final int BATCH = 5;
    /** An ingest claim older than this belongs to a replica that died; the job is taken over. */
    public static final int CLAIM_STALE_MINUTES = 30;

    private final BimImportJobRepository jobs;
    private final BimImportPipeline pipeline;
    private final BimImportIngestor ingestor;
    private final TenantScopedJobRunner tenantScopedJobRunner;
    /** Identifies this replica on the claims it takes. */
    private final String nodeId = nodeId();

    @Scheduled(fixedDelayString = "${echno.modules.bim.ingest.poll-interval-millis:5000}")
    @WithoutTenant("The import poller belongs to no organization: it reads job ids and their "
            + "organization as scalars, then pins each job's tenant before touching an entity")
    public void poll() {
        try {
            recoverExpiredLeases();
            mirrorClaims();
            closeFailed();
            ingestDone();
        } catch (Exception e) {
            // A thrown @Scheduled method is never rescheduled; log and try again next pass.
            log.error("BIM import poll failed: {}", e.getMessage(), e);
        }
    }

    void recoverExpiredLeases() {
        LocalDateTime now = LocalDateTime.now();
        int requeued = jobs.requeueExpiredLeases(now);
        if (requeued > 0) {
            log.warn("Returned {} BIM import job(s) to the queue after their lease expired", requeued);
        }
        int failed = jobs.failExpiredLeases(now,
                "The import worker stopped responding and no attempts were left. Queue the import again.");
        if (failed > 0) {
            log.error("Gave up on {} BIM import job(s) whose worker stopped responding", failed);
        }
    }

    void mirrorClaims() {
        for (JobRef ref : jobs.findRunningWithQueuedVersion()) {
            tenantScopedJobRunner.runForTenant(ref.getOrganizationId(), () -> ingestor.markProcessing(ref.getId()));
        }
    }

    void closeFailed() {
        for (JobRef ref : jobs.findClosedNotIngested("FAILED", BATCH)) {
            tenantScopedJobRunner.runForTenant(ref.getOrganizationId(), () ->
                    ingestor.markFailed(ref.getId(), ref.getError() == null
                            ? "The import worker failed without a message." : ref.getError()));
        }
    }

    void ingestDone() {
        List<JobRef> done = jobs.findClosedNotIngested("DONE", BATCH);
        for (JobRef ref : done) {
            // Every replica lists the same DONE jobs; the conditional claim decides which one
            // ingests each, so no version is advanced twice or failed by the loser (#814).
            LocalDateTime now = LocalDateTime.now();
            if (jobs.claimForIngest(ref.getId(), nodeId, now, now.minusMinutes(CLAIM_STALE_MINUTES)) != 1) {
                log.debug("BIM import job {} is claimed by another replica; skipping", ref.getId());
                continue;
            }
            log.info("Ingesting BIM import job {} for organization {} (claimed by {})", ref.getId(), ref.getOrganizationId(), nodeId);
            tenantScopedJobRunner.runForTenant(ref.getOrganizationId(), () -> pipeline.ingest(ref.getId(), nodeId));
        }
    }

    private static String nodeId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown";
        }
        String id = host + "/" + UUID.randomUUID().toString().substring(0, 8);
        return id.length() > 100 ? id.substring(id.length() - 100) : id;
    }
}
