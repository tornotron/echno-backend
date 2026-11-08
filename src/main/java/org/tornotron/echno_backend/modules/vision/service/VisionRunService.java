package org.tornotron.echno_backend.modules.vision.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.modules.inspections.api.EvidenceFile;
import org.tornotron.echno_backend.modules.inspections.api.EvidenceFiles;
import org.tornotron.echno_backend.modules.vision.VisionModuleEnabled;
import org.tornotron.echno_backend.modules.vision.domain.VisionJobTrigger;
import org.tornotron.echno_backend.modules.vision.dto.QueueVisionRunRequest;
import org.tornotron.echno_backend.modules.vision.dto.QueueVisionRunResult;
import org.tornotron.echno_backend.modules.vision.jobs.VisionJobQueue;
import org.tornotron.echno_backend.modules.vision.jobs.VisionJobQueue.RunnableVersion;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * A person asking for models to run on evidence images now. Works whether or not auto-inference is
 * on; the module entitlement is checked on the endpoint.
 */
@Service
@VisionModuleEnabled
@RequiredArgsConstructor
public class VisionRunService {

    public static final int MAX_IMAGES = 200;

    private final EvidenceFiles evidenceFiles;
    private final VisionJobQueue queue;
    private final VisionSettingsService settings;
    private final UserContextService userContextService;

    /**
     * @throws InvalidRequestException when the request names no source or more than one, more than
     *         {@value #MAX_IMAGES} files, a family with no ACTIVE version, or nothing at all to run
     * @throws ResourceNotFoundException when the inspection, the observation or an attachment is not
     *         evidence of this organization
     */
    @Transactional
    public QueueVisionRunResult queueRun(QueueVisionRunRequest req) {
        List<EvidenceFile> files = filesOf(req);
        if (files.size() > MAX_IMAGES) {
            throw new InvalidRequestException("A run takes up to " + MAX_IMAGES + " images; this one names " + files.size());
        }
        List<RunnableVersion> runnable = versionsFor(req.modelKeys());
        VisionJobQueue.Outcome outcome = queue.queue(files, runnable, VisionJobTrigger.ON_DEMAND,
                userContextService.getCurrentUserId(), Boolean.TRUE.equals(req.rerun()));
        return new QueueVisionRunResult(outcome.queued(), outcome.skippedDuplicate(), outcome.skippedNotImage());
    }

    private List<EvidenceFile> filesOf(QueueVisionRunRequest req) {
        boolean byInspection = req.inspectionId() != null;
        boolean byObservation = req.observationId() != null;
        boolean byAttachments = req.attachmentIds() != null && !req.attachmentIds().isEmpty();
        int named = (byInspection ? 1 : 0) + (byObservation ? 1 : 0) + (byAttachments ? 1 : 0);
        if (named != 1) {
            throw new InvalidRequestException("Name exactly one of inspectionId, observationId and attachmentIds");
        }
        if (byInspection) {
            return evidenceFiles.ofInspection(req.inspectionId());
        }
        if (byObservation) {
            return evidenceFiles.ofObservation(req.observationId());
        }
        List<EvidenceFile> files = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(req.attachmentIds())) {
            files.add(evidenceFiles.find(id).orElseThrow(() -> new ResourceNotFoundException(
                    "Attachment with ID " + id + " is not inspection or observation evidence of this organization")));
        }
        return files;
    }

    private List<RunnableVersion> versionsFor(List<String> requested) {
        if (requested != null && !requested.isEmpty()) {
            Set<String> keys = new LinkedHashSet<>(requested);
            List<RunnableVersion> runnable = queue.activeVersions(keys);
            Set<String> found = runnable.stream().map(RunnableVersion::modelKey).collect(Collectors.toSet());
            List<String> missing = keys.stream().filter(k -> !found.contains(k)).toList();
            if (!missing.isEmpty()) {
                throw new InvalidRequestException("No active version of model " + String.join(", ", missing));
            }
            return runnable;
        }
        List<RunnableVersion> runnable = queue.activeVersions(settings.get().modelKeys());
        if (runnable.isEmpty()) {
            throw new InvalidRequestException("No model family the organization runs has an active version");
        }
        return runnable;
    }
}
