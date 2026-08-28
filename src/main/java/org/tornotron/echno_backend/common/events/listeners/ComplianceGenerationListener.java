package org.tornotron.echno_backend.common.events.listeners;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.tornotron.echno_backend.common.events.ProjectApprovedEvent;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.compliance.ComplianceGenerationService;

/**
 * Runs AI compliance generation when a project is approved. It fires only AFTER the
 * approving transaction commits (so the project row is durable) and on a separate
 * async thread (so the slow AI call never blocks the approving request). That async
 * thread has no tenant context, so the organization id is read from the event and
 * established for the duration of the work by {@link TenantScopedJobRunner}.
 *
 * <h2>Why this listener owns no transaction</h2>
 *
 * <p>It used to be {@code @Transactional(REQUIRES_NEW)} and set the tenant context inside
 * the method body. That was wrong in two ways.
 *
 * <p>The transaction interceptor is pinned at {@code HIGHEST_PRECEDENCE} and
 * {@code HibernateFilterConfig} sits at {@code LOWEST_PRECEDENCE}, so the order on entry
 * was: open the transaction, run the filter aspect, run the body. The aspect read the
 * tenant context one step before the body set it, saw null, and never enabled
 * {@code orgFilter} for that transaction. The filter came on later only as a side effect
 * of the inner service call firing the same aspect on the already-open session, which is
 * an ordering accident rather than a design, and one that both isolation mechanisms would
 * have failed open on had it stopped holding.
 *
 * <p>The transaction also spanned the external model call, so the approval path pinned a
 * pool connection for its full 34 to 47 seconds. Being {@code @Async} moved the wait off
 * the request thread; it did nothing about the connection.
 *
 * <p>So the listener now does what a listener should: establish the tenant, delegate, and
 * let {@link ComplianceGenerationService} own its own transaction boundaries.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComplianceGenerationListener {

    private final ComplianceGenerationService complianceGenerationService;
    private final TenantScopedJobRunner tenantScopedJobRunner;

    @Async
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
            tenantScopedJobRunner.runForTenant(orgId,
                    () -> complianceGenerationService.generateForProject(projectId, orgId));
        } catch (InvalidRequestException | ResourceNotFoundException e) {
            // Expected on auto-approval: a project approved without a state in its address, or
            // before any rules exist for its jurisdiction, is normal. Log quietly and move on;
            // a project manager can regenerate once the precondition is met.
            log.info("Compliance auto-generation skipped for project {}: {}", projectId, e.getMessage());
        } catch (Exception e) {
            log.error("Compliance generation failed for project {} in organization {}: {}",
                    projectId, orgId, e.getMessage(), e);
        }
    }
}
