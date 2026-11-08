package org.tornotron.echno_backend.modules.documentingestion.apply;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.modules.documentingestion.domain.Candidate;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateKind;
import org.tornotron.echno_backend.modules.documentingestion.domain.CandidateStatus;
import org.tornotron.echno_backend.modules.documentingestion.domain.Provenance;
import org.tornotron.echno_backend.modules.documentingestion.domain.SourceRef;
import org.tornotron.echno_backend.modules.documentingestion.dto.ApplyResultDto;
import org.tornotron.echno_backend.modules.documentingestion.repository.CandidateRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.IngestPackageRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.ProvenanceRepository;
import org.tornotron.echno_backend.modules.documentingestion.repository.SourceRefRepository;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * The one code path that writes accepted values to live tables, inside a signed-in person's request.
 *
 * <p>Rows go in batches of {@value #BATCH} per transaction through {@link TransactionRetryTemplate},
 * and each row turns APPLIED with its provenance in the same transaction, so a 2,000-row BOQ needs
 * no long transaction and a stopped apply resumes where it stopped. If a batch fails, it is rolled
 * back and run again one row per transaction: the rows that can land do, and the one that cannot is
 * marked APPLY_FAILED with the reason. Nothing applied is applied twice, because only ACCEPTED rows
 * are picked.
 */
@Service
@PreAuthorize("isAuthenticated()")
@Slf4j
public class ApplyService {

    public static final int BATCH = 200;
    /** Batches per call, so one request stays bounded; the caller calls again while rows remain. */
    public static final int MAX_BATCHES_PER_CALL = 25;

    private final Map<CandidateKind, IngestionApplier> appliers;
    private final ApplyGuard guard;
    private final CandidateRepository candidates;
    private final SourceRefRepository sources;
    private final ProvenanceRepository provenance;
    private final IngestPackageRepository packages;
    private final TransactionRetryTemplate retry;
    private final UserContextService userContextService;

    public ApplyService(List<IngestionApplier> appliers, ApplyGuard guard, CandidateRepository candidates,
                        SourceRefRepository sources, ProvenanceRepository provenance, IngestPackageRepository packages,
                        TransactionRetryTemplate retry, UserContextService userContextService) {
        Map<CandidateKind, IngestionApplier> byKind = new EnumMap<>(CandidateKind.class);
        for (IngestionApplier applier : appliers) {
            if (byKind.put(applier.kind(), applier) != null) {
                throw new IllegalStateException("Two appliers write " + applier.kind());
            }
        }
        this.appliers = byKind;
        this.guard = guard;
        this.candidates = candidates;
        this.sources = sources;
        this.provenance = provenance;
        this.packages = packages;
        this.retry = retry;
        this.userContextService = userContextService;
    }

    public ApplyResultDto apply(UUID packageId, CandidateKind kind) {
        ApplyGuard.requireSignedIn();
        IngestionApplier applier = appliers.get(kind);
        if (applier == null) {
            throw new InvalidRequestException("Accepted " + kind + " values cannot be applied yet");
        }
        guard.require(applier.targetGuard());
        retry.execute("DocumentIngestion.requirePackage", () -> packages.findByIdScoped(packageId)
                .orElseThrow(() -> new ResourceNotFoundException("No document package " + packageId)));
        retry.executeWithoutResult("DocumentIngestion.preflight", () -> applier.preflight(packageId));
        Long appliedBy = userContextService.getCurrentUserId();

        int applied = 0;
        int failed = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_CALL; batch++) {
            List<UUID> ids = retry.execute("DocumentIngestion.nextBatch",
                    () -> candidates.findIdsToApply(packageId, kind, PageRequest.of(0, BATCH)));
            if (ids.isEmpty()) {
                break;
            }
            try {
                retry.executeWithoutResult("DocumentIngestion.applyBatch",
                        () -> ids.forEach(id -> applyOne(applier, id, appliedBy)));
                applied += ids.size();
            } catch (RuntimeException batchFailure) {
                log.warn("Applying a batch of {} {} candidates failed ({}); applying them one at a time",
                        ids.size(), kind, batchFailure.getMessage());
                for (UUID id : ids) {
                    try {
                        retry.executeWithoutResult("DocumentIngestion.applyOne", () -> applyOne(applier, id, appliedBy));
                        applied++;
                    } catch (RuntimeException rowFailure) {
                        failed++;
                        String reason = rowFailure.getMessage() == null
                                ? rowFailure.getClass().getSimpleName() : rowFailure.getMessage();
                        retry.executeWithoutResult("DocumentIngestion.markApplyFailed", () -> markFailed(id, reason));
                    }
                }
            }
        }
        long remaining = retry.execute("DocumentIngestion.remaining",
                () -> candidates.countByStatus(packageId, kind, CandidateStatus.ACCEPTED));
        return new ApplyResultDto(applied, failed, remaining);
    }

    private void applyOne(IngestionApplier applier, UUID candidateId, Long appliedBy) {
        Candidate c = candidates.findByIdScoped(candidateId)
                .orElseThrow(() -> new ResourceNotFoundException("No candidate " + candidateId));
        if (c.getStatus() != CandidateStatus.ACCEPTED) {
            return;
        }
        Map<String, Object> accepted = c.getAcceptedPayload() == null ? c.getPayload() : c.getAcceptedPayload();
        IngestionApplier.AppliedTarget target = applier.apply(c, accepted);
        LocalDateTime now = LocalDateTime.now();
        Map<String, SourceRef> refByField = sources.findForCandidates(List.of(candidateId)).stream()
                .collect(Collectors.toMap(SourceRef::getField, Function.identity(), (first, second) -> first));
        target.fields().forEach((payloadField, targetField) -> {
            Provenance p = new Provenance();
            p.setOrganization(c.getOrganization());
            p.setTargetType(target.targetType());
            p.setTargetId(target.targetId());
            p.setTargetField(targetField);
            p.setValueExtracted(text(c.getPayload().get(payloadField)));
            p.setValueAccepted(text(accepted.get(payloadField)));
            SourceRef ref = refByField.get(payloadField);
            p.setSourceRefId(ref == null ? null : ref.getId());
            p.setEnteredByHand(ref == null && c.getPayload().get(payloadField) == null);
            p.setCandidateId(c.getId());
            p.setAcceptedBy(c.getReviewedBy());
            p.setAcceptedAt(c.getReviewedAt());
            p.setAppliedBy(appliedBy);
            p.setAppliedAt(now);
            provenance.save(p);
        });
        c.setStatus(CandidateStatus.APPLIED);
        c.setAppliedTargetType(target.targetType());
        c.setAppliedTargetId(target.targetId());
        c.setAppliedBy(appliedBy);
        c.setAppliedAt(now);
        c.setApplyError(null);
        candidates.save(c);
    }

    private void markFailed(UUID candidateId, String reason) {
        candidates.findByIdScoped(candidateId).ifPresent(c -> {
            c.setStatus(CandidateStatus.APPLY_FAILED);
            c.setApplyError(reason);
            candidates.save(c);
        });
    }

    private static String text(Object value) {
        return value == null ? null : Objects.toString(value);
    }
}
