package org.tornotron.echno_backend.modules.vision.jobs;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.modules.vision.VisionModuleEnabled;
import org.tornotron.echno_backend.modules.vision.domain.VisionInferenceJob;
import org.tornotron.echno_backend.modules.vision.domain.VisionJobStatus;
import org.tornotron.echno_backend.modules.vision.repository.VisionInferenceJobRepository;

/**
 * The two short transactions around an ingestion, kept apart from the intake calls between them,
 * which open a transaction per item and must not run inside one.
 */
@Component
@VisionModuleEnabled
@RequiredArgsConstructor
public class VisionJobStore {

    private final VisionInferenceJobRepository jobs;

    /** A DONE job of the pinned tenant that has not been ingested, detached from the session. */
    @Transactional(readOnly = true)
    public Optional<VisionInferenceJob> doneNotIngested(UUID id) {
        return jobs.findById(id)
                .filter(j -> j.getStatus() == VisionJobStatus.DONE && j.getIngestedAt() == null);
    }

    @Transactional
    public void markIngested(UUID id, int observationCount, String ingestError) {
        jobs.findById(id).ifPresent(j -> {
            LocalDateTime now = LocalDateTime.now();
            j.setIngestedAt(now);
            j.setObservationCount(observationCount);
            j.setIngestError(ingestError);
            j.setUpdatedAt(now);
        });
    }
}
