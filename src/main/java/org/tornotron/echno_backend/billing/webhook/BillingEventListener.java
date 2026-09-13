package org.tornotron.echno_backend.billing.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Hands a committed inbox row to the projector on another thread. Kept separate from the
 * projector on purpose: the {@code @WithoutTenant} declaration the projector needs is made
 * by an aspect around the projector's own method, and an aspect on an {@code @Async} method
 * would run on the caller's thread and leave the worker undeclared.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingEventListener {

    private final BillingEventProjector projector;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReceived(BillingEventReceived received) {
        try {
            projector.process(received.billingEventId());
        } catch (RuntimeException e) {
            // process() records the failure on the row; this is the last line of defence so a
            // failure in that recording does not vanish into the executor.
            log.error("Billing event {} failed outside the projector's own handling: {}", received.billingEventId(), e.getMessage(), e);
        }
    }
}
