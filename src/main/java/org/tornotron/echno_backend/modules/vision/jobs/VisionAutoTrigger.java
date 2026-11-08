package org.tornotron.echno_backend.modules.vision.jobs;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.module.ModuleRegistry;
import org.tornotron.echno_backend.modules.inspections.api.EvidenceFile;
import org.tornotron.echno_backend.modules.vision.VisionModule;
import org.tornotron.echno_backend.modules.vision.VisionModuleEnabled;
import org.tornotron.echno_backend.modules.vision.domain.VisionJobTrigger;
import org.tornotron.echno_backend.modules.vision.service.VisionSettingsService;

/**
 * Decides whether a new evidence image is run automatically and queues it. Nothing is queued unless
 * the organization has the module (switched on, entitled, its dependencies enabled) and its settings
 * have auto-inference on.
 */
@Service
@VisionModuleEnabled
@RequiredArgsConstructor
public class VisionAutoTrigger {

    private final ModuleRegistry moduleRegistry;
    private final VisionSettingsService settings;
    private final VisionJobQueue queue;

    /**
     * In a transaction of its own, since it runs after the upload's transaction has committed. The
     * tenant must be pinned to the image's organization.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VisionJobQueue.Outcome onImage(EvidenceFile file) {
        if (!moduleRegistry.isEnabledForOrg(VisionModule.ID, file.organizationId())) {
            return VisionJobQueue.Outcome.NONE;
        }
        Optional<List<String>> keys = settings.autoInferModelKeys();
        if (keys.isEmpty()) {
            return VisionJobQueue.Outcome.NONE;
        }
        List<VisionJobQueue.RunnableVersion> runnable = queue.activeVersions(keys.get());
        if (runnable.isEmpty()) {
            return VisionJobQueue.Outcome.NONE;
        }
        return queue.queue(List.of(file), runnable, VisionJobTrigger.AUTO, null, false);
    }
}
