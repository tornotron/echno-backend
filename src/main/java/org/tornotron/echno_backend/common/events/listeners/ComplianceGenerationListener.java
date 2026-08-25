package org.tornotron.echno_backend.common.events.listeners;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.tornotron.echno_backend.common.events.ProjectApprovedEvent;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.compliance.ComplianceGenerationService;

/**
 * Runs AI compliance generation when a project is approved. It fires only AFTER the
 * approving transaction commits (so the project row is durable) and on a separate
 * async thread (so the slow AI call never blocks the approving request). That async
 * thread has no {@link TenantContext}, so the organization id is read from the event
 * and set on the thread for the duration of the tenant-scoped work, then cleared.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComplianceGenerationListener {

    private final ComplianceGenerationService complianceGenerationService;

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProjectApproved(ProjectApprovedEvent event) {
        Long projectId = event.getProjectId();
        Long orgId = event.getOrganizationId();
        if (orgId == null) {
            log.warn("ProjectApprovedEvent for project {} has no organization id; skipping compliance generation",
                    projectId);
            return;
        }

        log.info("Project {} approved; generating compliance inspections for organization {}", projectId, orgId);
        try {
            TenantContext.setCurrentOrgId(orgId);
            complianceGenerationService.generateForProject(projectId, orgId);
        } catch (InvalidRequestException | ResourceNotFoundException e) {
            // Expected on auto-approval: a project approved without a state in its address, or
            // before any rules exist for its jurisdiction, is normal. Log quietly and move on;
            // a project manager can regenerate once the precondition is met.
            log.info("Compliance auto-generation skipped for project {}: {}", projectId, e.getMessage());
        } catch (Exception e) {
            log.error("Compliance generation failed for project {} in organization {}: {}",
                    projectId, orgId, e.getMessage(), e);
        } finally {
            TenantContext.clear();
        }
    }
}
