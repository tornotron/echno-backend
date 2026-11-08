package org.tornotron.echno_backend.modules.vision.jobs;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.common.multitenancy.WithoutTenant;
import org.tornotron.echno_backend.modules.vision.VisionModuleEnabled;
import org.tornotron.echno_backend.modules.vision.repository.VisionInferenceJobRepository;
import org.tornotron.echno_backend.modules.vision.repository.VisionInferenceJobRepository.JobRef;

/**
 * The backend's side of the job contract: returns jobs whose worker let the lease expire to the
 * queue, fails those with no attempts left, and ingests DONE jobs. Reads scalars across tenants, then
 * pins each job's tenant before any entity is touched, the same shape as the BIM import poller.
 *
 * <p>Two replicas may list the same DONE job. Both ingesting it is harmless: the external refs repeat,
 * so the second pass finds every observation already there, and the stamp is the same.
 */
@Component
@VisionModuleEnabled
@ConditionalOnProperty(name = VisionJobPoller.PROPERTY, havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class VisionJobPoller {

    public static final String PROPERTY = "echno.vision.ingest.enabled";
    public static final int BATCH = 10;
    static final String NO_ATTEMPTS_LEFT =
            "The vision worker stopped responding and no attempts were left. Run the model again.";

    private final VisionInferenceJobRepository jobs;
    private final VisionDetectionIngestor ingestor;
    private final TenantScopedJobRunner tenantScopedJobRunner;

    @Scheduled(fixedDelayString = "${echno.vision.ingest.poll-interval-millis:10000}")
    @WithoutTenant("The vision job poller belongs to no organization: it reads job ids and their "
            + "organization as scalars, then pins each job's tenant before touching an entity")
    public void poll() {
        try {
            recoverExpiredLeases();
            ingestDone();
        } catch (Exception e) {
            // A thrown @Scheduled method is never rescheduled; log and try again next pass.
            log.error("Vision job poll failed: {}", e.getMessage(), e);
        }
    }

    void recoverExpiredLeases() {
        LocalDateTime now = LocalDateTime.now();
        int requeued = jobs.requeueExpiredLeases(now);
        if (requeued > 0) {
            log.warn("Returned {} vision job(s) to the queue after their lease expired", requeued);
        }
        int failed = jobs.failExpiredLeases(now, NO_ATTEMPTS_LEFT);
        if (failed > 0) {
            log.error("Gave up on {} vision job(s) whose worker stopped responding", failed);
        }
    }

    void ingestDone() {
        for (JobRef ref : jobs.findDoneNotIngested(BATCH)) {
            try {
                tenantScopedJobRunner.runForTenant(ref.getOrganizationId(), () -> ingestor.ingest(ref.getId()));
            } catch (RuntimeException e) {
                // One job's failure must not hold up the rest; it is listed again next pass.
                log.error("Vision job {} could not be ingested: {}", ref.getId(), e.getMessage(), e);
            }
        }
    }
}
