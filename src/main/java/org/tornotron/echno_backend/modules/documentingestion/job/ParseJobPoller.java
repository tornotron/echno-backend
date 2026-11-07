package org.tornotron.echno_backend.modules.documentingestion.job;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.common.multitenancy.WithoutTenant;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionModuleEnabled;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionSwitch;
import org.tornotron.echno_backend.modules.documentingestion.repository.ParseJobRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.ParseJobRepository.JobRef;

/**
 * The backend's side of the worker contract: returns jobs whose lease ran out, fails those with no
 * attempts left, closes queued work of an organization taken off the allow-list, and reads closed
 * jobs back into their documents. Exists only with the operator switch on; with it off, rows wait.
 * Reads scalars across tenants, then pins each job's tenant before any entity is touched.
 */
@Component
@DocumentIngestionModuleEnabled
@Slf4j
public class ParseJobPoller {

    public static final int BATCH = 10;
    /** An ingest claim older than this belongs to a replica that died; the job is taken over. */
    public static final int CLAIM_STALE_MINUTES = 15;

    static final String LEASE_GIVE_UP = "The file reader stopped responding three times. Read the file again, "
            + "and if it fails the same way, the file may be too large or damaged.";
    static final String REMOVED_FROM_ALLOW_LIST = "Document import was turned off for this organization before "
            + "the file was read.";

    private final ParseJobRepository jobs;
    private final ParseJobIngestor ingestor;
    private final TenantScopedJobRunner tenantScopedJobRunner;
    private final DocumentIngestionSwitch operatorSwitch;
    private final String nodeId = nodeId();

    public ParseJobPoller(ParseJobRepository jobs, ParseJobIngestor ingestor, TenantScopedJobRunner tenantScopedJobRunner,
                          Environment environment) {
        this.jobs = jobs;
        this.ingestor = ingestor;
        this.tenantScopedJobRunner = tenantScopedJobRunner;
        this.operatorSwitch = new DocumentIngestionSwitch(environment);
    }

    @Scheduled(fixedDelayString = "${echno.modules.document-ingestion.parse.poll-interval-millis:5000}")
    @WithoutTenant("The parse poller belongs to no organization: it reads job ids and their organization as "
            + "scalars, then pins each job's tenant before touching an entity")
    public void poll() {
        try {
            recoverExpiredLeases();
            closeQueuedOutsideTheAllowList();
            ingestClosed("FAILED");
            ingestClosed("DONE");
        } catch (Exception e) {
            // A thrown @Scheduled method is never rescheduled; log and try again next pass.
            log.error("Document ingestion parse poll failed: {}", e.getMessage(), e);
        }
    }

    void recoverExpiredLeases() {
        LocalDateTime now = LocalDateTime.now();
        int requeued = jobs.requeueExpiredLeases(now);
        if (requeued > 0) {
            log.warn("Returned {} parse job(s) to the queue after their lease expired", requeued);
        }
        int failed = jobs.failExpiredLeases(now, LEASE_GIVE_UP);
        if (failed > 0) {
            log.error("Gave up on {} parse job(s) whose worker stopped responding", failed);
        }
    }

    void closeQueuedOutsideTheAllowList() {
        for (Long organizationId : jobs.findOrganizationsWithQueuedJobs()) {
            if (!operatorSwitch.allows(organizationId)) {
                int closed = jobs.failQueuedForOrganization(organizationId, LocalDateTime.now(), REMOVED_FROM_ALLOW_LIST);
                log.warn("Closed {} queued parse job(s) of organization {}, which is not on the allow-list",
                        closed, organizationId);
            }
        }
    }

    void ingestClosed(String status) {
        for (JobRef ref : jobs.findClosedNotIngested(status, BATCH)) {
            LocalDateTime now = LocalDateTime.now();
            if (jobs.claimForIngest(ref.getId(), ref.getOrganizationId(), nodeId, now,
                    now.minusMinutes(CLAIM_STALE_MINUTES)) != 1) {
                continue;
            }
            try {
                tenantScopedJobRunner.runForTenant(ref.getOrganizationId(), () -> ingestor.ingest(ref.getId(), nodeId));
            } catch (Exception e) {
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.error("Parse job {} could not be read back: {}", ref.getId(), reason, e);
                tenantScopedJobRunner.runForTenant(ref.getOrganizationId(), () ->
                        ingestor.markFailed(ref.getId(), "The file was read but its result could not be loaded: " + reason));
            }
        }
    }

    String node() {
        return nodeId;
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
