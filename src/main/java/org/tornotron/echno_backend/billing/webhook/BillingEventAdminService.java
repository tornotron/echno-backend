package org.tornotron.echno_backend.billing.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.billing.gateway.BillingEvent;
import org.tornotron.echno_backend.billing.gateway.BillingEventStatus;
import org.tornotron.echno_backend.billing.repositories.BillingEventRepository;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;

import java.util.Collection;
import java.util.List;

/**
 * Dead-letter visibility for the webhook inbox (spec section 6.3): the rows the projector
 * gave up on or set aside, and a retry that hands one back to it.
 *
 * <p>The inbox is global, so the list is not filtered by the caller's organization; it is a
 * platform-operator view and the endpoint is guarded accordingly. The organization filter
 * is a convenience for narrowing, not a boundary.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingEventAdminService {

    /** What "dead letter" means when the caller does not say: given up on, or set aside. */
    public static final List<BillingEventStatus> DEAD_LETTER_STATUSES =
            List.of(BillingEventStatus.FAILED, BillingEventStatus.SKIPPED);

    private final BillingEventRepository events;
    private final BillingEventProjector projector;

    /**
     * Lists inbox rows, newest first.
     *
     * @param organizationId Narrow to one organization; null for all.
     * @param eventType Narrow to one provider event type; null for all.
     * @param statuses Which statuses to show; empty for the dead-letter pair.
     * @param pageable Page and size.
     * @return The page.
     */
    public Page<BillingEventDto> list(Long organizationId, String eventType,
                                      Collection<BillingEventStatus> statuses, Pageable pageable) {
        Collection<BillingEventStatus> wanted = statuses == null || statuses.isEmpty() ? DEAD_LETTER_STATUSES : statuses;
        Specification<BillingEvent> spec = (root, query, cb) -> root.get("status").in(wanted);
        if (organizationId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("organizationId"), organizationId));
        }
        if (eventType != null && !eventType.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("eventType"), eventType.trim()));
        }
        return events.findAll(spec, pageable).map(BillingEventDto::from);
    }

    /**
     * Hands one row back to the projector. A FAILED or SKIPPED row is returned to RECEIVED
     * with a fresh attempt budget and projected now, synchronously, so the response says what
     * happened. A RECEIVED row is simply projected. A PROCESSED row is left alone and returned
     * as is: retrying it is a no-op, which is what makes a double click harmless.
     *
     * @param billingEventId The inbox row.
     * @return The row after the attempt.
     */
    public BillingEventDto retry(Long billingEventId) {
        BillingEvent row = events.findById(billingEventId)
                .orElseThrow(() -> new ResourceNotFoundException("Billing event " + billingEventId + " not found"));
        if (row.getStatus() == BillingEventStatus.PROCESSED) {
            return BillingEventDto.from(row);
        }
        if (!Boolean.TRUE.equals(row.getSignatureVerified())) {
            throw new InvalidRequestException("Billing event " + billingEventId + " was never signature-verified; refusing to project it");
        }
        if (row.getStatus() != BillingEventStatus.RECEIVED) {
            log.info("Billing event {} ({}) re-queued by an administrator after {} attempt(s): {}",
                    row.getId(), row.getEventType(), row.getAttemptCount(), row.getLastError());
            row.setStatus(BillingEventStatus.RECEIVED);
            row.setAttemptCount(0);
            row.setProcessedAt(null);
            events.save(row);
        }
        projector.process(billingEventId);
        return events.findById(billingEventId).map(BillingEventDto::from)
                .orElseThrow(() -> new ResourceNotFoundException("Billing event " + billingEventId + " vanished during retry"));
    }
}
