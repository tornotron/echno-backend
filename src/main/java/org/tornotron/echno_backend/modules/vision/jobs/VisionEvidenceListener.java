package org.tornotron.echno_backend.modules.vision.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.modules.inspections.api.EvidenceFile;
import org.tornotron.echno_backend.modules.inspections.api.EvidenceImageRegistered;
import org.tornotron.echno_backend.modules.vision.VisionModuleEnabled;

/**
 * The automatic trigger: once an inspection or observation evidence image is committed, queues one
 * job per family the organization runs, when the organization has the module and auto-inference on.
 * Runs after commit, so a rolled-back upload queues nothing, and in a transaction of its own. A
 * failure here is logged and never reaches the upload, which has already succeeded.
 */
@Slf4j
@Component
@VisionModuleEnabled
@RequiredArgsConstructor
public class VisionEvidenceListener {

    private final VisionAutoTrigger trigger;
    private final TenantScopedJobRunner tenantScopedJobRunner;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEvidenceImage(EvidenceImageRegistered event) {
        EvidenceFile file = event.file();
        if (file == null || file.organizationId() == null) {
            return;
        }
        try {
            tenantScopedJobRunner.runForTenant(file.organizationId(), () -> trigger.onImage(file));
        } catch (RuntimeException e) {
            log.error("Could not queue vision jobs for attachment {} of organization {}: {}", file.attachmentId(),
                    file.organizationId(), e.getMessage(), e);
        }
    }
}
