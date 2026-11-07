package org.tornotron.echno_backend.modules.inspections.service;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.modules.inspections.api.IntakeActor;
import org.tornotron.echno_backend.modules.inspections.api.IntakeRejectedException;
import org.tornotron.echno_backend.modules.inspections.dtos.IntakeBatchResponse;
import org.tornotron.echno_backend.modules.inspections.dtos.IntakeObservationRequest;
import org.tornotron.echno_backend.modules.inspections.observation.IntakeReferenceNotFoundException;
import org.tornotron.echno_backend.modules.inspections.observation.IntakeRejectedCodes;

/**
 * Many findings in one call, each through the same path as a single intake. Each item is its own
 * transaction, restart on a lost race included, so one bad item never rolls back the others; for
 * that this class opens no transaction, and a caller must not hold one open around it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ObservationBatchIntakeService {

    private final ObservationService observations;
    private final Validator validator;

    /**
     * @param items the findings, in order
     * @param actor who the event log names, or null to name each item's own producer
     * @return one result per item, in the same order
     */
    public IntakeBatchResponse intakeAll(List<IntakeObservationRequest> items, IntakeActor actor) {
        List<IntakeBatchResponse.Item> results = new ArrayList<>(items.size());
        Set<String> seen = new HashSet<>();
        int created = 0;
        int existing = 0;
        int rejected = 0;
        for (int index = 0; index < items.size(); index++) {
            IntakeBatchResponse.Item result = intakeOne(index, items.get(index), actor, seen);
            results.add(result);
            switch (result.status()) {
                case CREATED -> created++;
                case EXISTING -> existing++;
                case REJECTED -> rejected++;
            }
        }
        log.info("Batch intake: {} created, {} existing, {} rejected", created, existing, rejected);
        return new IntakeBatchResponse(created, existing, rejected, results);
    }

    private IntakeBatchResponse.Item intakeOne(int index, IntakeObservationRequest item, IntakeActor actor,
                                               Set<String> seen) {
        if (item == null) {
            return rejected(index, null, IntakeRejectedCodes.VALIDATION, null, "The item is empty");
        }
        String ref = item.externalRef();
        Set<ConstraintViolation<IntakeObservationRequest>> violations = validator.validate(item);
        if (!violations.isEmpty()) {
            ConstraintViolation<IntakeObservationRequest> first = violations.iterator().next();
            return rejected(index, ref, IntakeRejectedCodes.VALIDATION, first.getPropertyPath().toString(),
                    first.getPropertyPath() + " " + first.getMessage());
        }
        if (!seen.add(ref)) {
            return rejected(index, ref, IntakeRejectedCodes.DUPLICATE_IN_BATCH, "externalRef",
                    "externalRef '" + ref + "' appears earlier in this batch");
        }
        try {
            ObservationService.IntakeResult result = observations.intake(item, actor);
            return new IntakeBatchResponse.Item(index, ref,
                    result.created() ? IntakeBatchResponse.Status.CREATED : IntakeBatchResponse.Status.EXISTING,
                    result.observation().id(), null);
        } catch (IntakeRejectedException e) {
            return rejected(index, ref, e.getCode(), e.getField(), e.getMessage());
        } catch (IntakeReferenceNotFoundException e) {
            return rejected(index, ref, e.getCode(), null, e.getMessage());
        } catch (InvalidRequestException | ResourceNotFoundException | IllegalArgumentException e) {
            return rejected(index, ref, IntakeRejectedCodes.VALIDATION, null, e.getMessage());
        } catch (RuntimeException e) {
            // A failure of ours, not the item's: logged in full, and the caller gets no internals.
            log.error("Batch intake item {} ({}) failed: {}", index, ref, e.getMessage(), e);
            return rejected(index, ref, IntakeRejectedCodes.INTERNAL, null, "The item could not be stored");
        }
    }

    private static IntakeBatchResponse.Item rejected(int index, String ref, String code, String field, String message) {
        return new IntakeBatchResponse.Item(index, ref, IntakeBatchResponse.Status.REJECTED, null,
                new IntakeBatchResponse.Error(code, field, message));
    }
}
