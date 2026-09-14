package org.tornotron.echno_backend.modules.bim.importer;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * One DONE job, start to finish, under the tenant the poller pinned. Every ending is written
 * to the version: READY with the counts, or FAILED with the reason. Throws nothing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BimImportPipeline {

    private final BimImportIngestor ingestor;

    public void ingest(UUID jobId) {
        ingest(jobId, null);
    }

    /**
     * As {@link #ingest(UUID)}, fenced on the ingest claim: every write checks that the job
     * is still claimed by {@code claimedBy}, so a replica whose claim was taken over after the
     * stale window cannot commit over the replica that took it.
     */
    public void ingest(UUID jobId, String claimedBy) {
        try {
            ingestor.ingest(jobId, claimedBy);
        } catch (BimImportIngestor.ClaimLostException lost) {
            log.warn("BIM import job {}: {}", jobId, lost.getMessage());
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("BIM import job {} could not be ingested: {}", jobId, reason, e);
            try {
                ingestor.markFailed(jobId, "Import could not be read into Echno: " + reason, claimedBy);
            } catch (Exception inner) {
                log.error("BIM import job {}: recording the failure also failed: {}", jobId, inner.getMessage(), inner);
            }
        }
    }
}
