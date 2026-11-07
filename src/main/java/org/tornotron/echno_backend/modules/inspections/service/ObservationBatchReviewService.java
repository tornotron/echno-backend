package org.tornotron.echno_backend.modules.inspections.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.modules.inspections.ObservationReviewDecision;
import org.tornotron.echno_backend.modules.inspections.dtos.ReviewBatchRequest;
import org.tornotron.echno_backend.modules.inspections.dtos.ReviewBatchResponse;
import org.tornotron.echno_backend.modules.inspections.dtos.ReviewObservationRequest;
import org.tornotron.echno_backend.modules.inspections.observation.ObservationAlreadyReviewedException;

/**
 * One reviewer's decision on many observations. Only the decisions that need no more than the tile:
 * accept with no outcome, or reject. Creating a defect, setting a check item or a MODIFY stays in the
 * single-item review, where the reviewer sees the whole photo. Each observation is its own transaction,
 * so one that cannot take the decision leaves the others alone and is answered with its own status.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ObservationBatchReviewService {

    private final ObservationService observations;
    private final TransactionRetryTemplate retryTemplate;

    /**
     * @param includeDetections whether the caller may see a model's detections; an id it may not see
     *                          answers NOT_FOUND
     */
    public ReviewBatchResponse review(ReviewBatchRequest req, boolean includeDetections) {
        if (req.decision() == ObservationReviewDecision.MODIFY) {
            throw new InvalidRequestException("MODIFY is a single-item decision; use the observation's own review");
        }
        if (req.rejectReason() != null && req.decision() != ObservationReviewDecision.REJECT) {
            throw new InvalidRequestException("rejectReason is for a REJECT decision only");
        }
        UUID batchId = UUID.randomUUID();
        ReviewObservationRequest decision = new ReviewObservationRequest(req.decision(), req.note(), null,
                ReviewObservationRequest.Outcome.none(), req.rejectReason());
        List<ReviewBatchResponse.Result> results = new ArrayList<>(req.observationIds().size());
        for (UUID id : req.observationIds()) {
            results.add(reviewOne(id, decision, batchId, includeDetections));
        }
        long reviewed = results.stream().filter(r -> r.status() == ReviewBatchResponse.Status.REVIEWED).count();
        log.info("Batch review {}: {} of {} reviewed as {}", batchId, reviewed, results.size(), req.decision());
        return new ReviewBatchResponse(batchId, results);
    }

    private ReviewBatchResponse.Result reviewOne(UUID id, ReviewObservationRequest decision, UUID batchId,
                                                 boolean includeDetections) {
        try {
            retryTemplate.executeWithoutResult("ObservationBatchReviewService.review",
                    () -> observations.reviewInBatch(id, decision, batchId, includeDetections));
            return new ReviewBatchResponse.Result(id, ReviewBatchResponse.Status.REVIEWED, null);
        } catch (ResourceNotFoundException e) {
            return new ReviewBatchResponse.Result(id, ReviewBatchResponse.Status.NOT_FOUND, null);
        } catch (ObservationAlreadyReviewedException e) {
            return new ReviewBatchResponse.Result(id, ReviewBatchResponse.Status.ALREADY_REVIEWED, null);
        } catch (InvalidRequestException e) {
            return new ReviewBatchResponse.Result(id, ReviewBatchResponse.Status.REFUSED, e.getMessage());
        } catch (RuntimeException e) {
            // A failure of ours, not the decision's: logged in full, and the caller gets no internals.
            log.error("Batch review {} failed on observation {}: {}", batchId, id, e.getMessage(), e);
            return new ReviewBatchResponse.Result(id, ReviewBatchResponse.Status.REFUSED, "The decision could not be stored");
        }
    }
}
