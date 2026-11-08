package org.tornotron.echno_backend.modules.documentingestion.service;

import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractJob;
import org.tornotron.echno_backend.modules.documentingestion.domain.ExtractJobStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestPackage;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestSettingChange;
import org.tornotron.echno_backend.modules.documentingestion.dto.ExtractJobDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.LlmBudgetRequest;
import org.tornotron.echno_backend.modules.documentingestion.dto.LlmCallDto;
import org.tornotron.echno_backend.modules.documentingestion.dto.LlmSpendDto;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.LlmBudget;
import org.tornotron.echno_backend.modules.documentingestion.repository.ExtractJobRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestPackageRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestSettingChangeRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.LlmCallRepository;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * The extract queue as a person sees it: a package's jobs, "retry failed parts", the ledger of what
 * was sent, the spend against the budget, and an admin raising a package's limit.
 */
@Service
public class ExtractionService {

    static final int MAX_PAGE_SIZE = 200;

    private final ExtractJobRepository jobs;
    private final LlmCallRepository ledger;
    private final IngestPackageRepository packages;
    private final IngestSettingChangeRepository changes;
    private final LlmBudget budget;
    private final TransactionRetryTemplate retry;
    private final UserContextService userContextService;

    public ExtractionService(ExtractJobRepository jobs, LlmCallRepository ledger, IngestPackageRepository packages,
                             IngestSettingChangeRepository changes, LlmBudget budget, TransactionRetryTemplate retry,
                             UserContextService userContextService) {
        this.jobs = jobs;
        this.ledger = ledger;
        this.packages = packages;
        this.changes = changes;
        this.budget = budget;
        this.retry = retry;
        this.userContextService = userContextService;
    }

    public Page<ExtractJobDto> jobs(UUID packageId, int page, int size) {
        return retry.execute("DocumentIngestion.extractJobs", () -> {
            requirePackage(packageId);
            return jobs.findByPackage(packageId, pageOf(page, size)).map(ExtractJobDto::of);
        });
    }

    /**
     * Runs a finished job again. Chunks already read have an {@code OK} ledger row and are skipped, so
     * only the failed parts are sent.
     */
    public ExtractJobDto retryFailed(UUID jobId) {
        return retry.execute("DocumentIngestion.extractRetryFailed", () -> {
            ExtractJob job = jobs.findByIdScoped(jobId)
                    .orElseThrow(() -> new ResourceNotFoundException("No extract job " + jobId));
            if (job.getStatus() != ExtractJobStatus.PARTIAL && job.getStatus() != ExtractJobStatus.FAILED) {
                throw new InvalidRequestException("Only a job that ended PARTIAL or FAILED is retried; this one is "
                        + job.getStatus());
            }
            job.setStatus(ExtractJobStatus.QUEUED);
            job.setAttempt(0);
            job.setMessage(null);
            job.setFinishedAt(null);
            job.setQueuedAt(LocalDateTime.now());
            return ExtractJobDto.of(jobs.save(job));
        });
    }

    /** The ledger, newest first, across the organization or narrowed to a package or a document. */
    public Page<LlmCallDto> calls(UUID packageId, UUID documentId, int page, int size) {
        return retry.execute("DocumentIngestion.llmCalls", () -> {
            if (packageId != null) {
                requirePackage(packageId);
            }
            return ledger.findForAudit(packageId, documentId, pageOf(page, size)).map(LlmCallDto::of);
        });
    }

    public LlmSpendDto spend(UUID packageId) {
        retry.execute("DocumentIngestion.spendPackage", () -> {
            requirePackage(packageId);
            return null;
        });
        return LlmSpendDto.of(budget.standing(TenantContext.getCurrentOrgId(), packageId));
    }

    /** Sets a package's limits; null goes back to the default. A paused job resumes on the next poll. */
    public LlmSpendDto setPackageBudget(UUID packageId, LlmBudgetRequest req) {
        Long userId = userContextService.getCurrentUserId();
        retry.executeWithoutResult("DocumentIngestion.packageBudget", () -> {
            IngestPackage p = packages.findByIdScoped(packageId)
                    .orElseThrow(() -> new ResourceNotFoundException("No document package " + packageId));
            LocalDateTime now = LocalDateTime.now();
            record(p, IngestSettingChange.PACKAGE_BUDGET_USD,
                    p.getLlmBudgetUsd() == null ? null : p.getLlmBudgetUsd().toPlainString(),
                    req.budgetUsd() == null ? null : req.budgetUsd().toPlainString(), userId, now);
            record(p, IngestSettingChange.PACKAGE_BUDGET_TOKENS,
                    p.getLlmBudgetTokens() == null ? null : p.getLlmBudgetTokens().toString(),
                    req.budgetTokens() == null ? null : req.budgetTokens().toString(), userId, now);
            p.setLlmBudgetUsd(req.budgetUsd());
            p.setLlmBudgetTokens(req.budgetTokens());
            p.setUpdatedBy(userId);
            packages.save(p);
        });
        return spend(packageId);
    }

    private void record(IngestPackage p, String setting, String before, String after, Long userId, LocalDateTime now) {
        if (java.util.Objects.equals(before, after)) {
            return;
        }
        IngestSettingChange change = new IngestSettingChange();
        change.setOrganization(p.getOrganization());
        change.setSetting(setting);
        // The package is named in the value: the change log is the organization's, one table for all settings.
        change.setOldValue(before == null ? null : p.getId() + " " + before);
        change.setNewValue(p.getId() + " " + (after == null ? "default" : after));
        change.setChangedBy(userId);
        change.setChangedAt(now);
        changes.save(change);
    }

    private void requirePackage(UUID packageId) {
        if (packages.findByIdScoped(packageId).isEmpty()) {
            throw new ResourceNotFoundException("No document package " + packageId);
        }
    }

    private static PageRequest pageOf(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    }
}
