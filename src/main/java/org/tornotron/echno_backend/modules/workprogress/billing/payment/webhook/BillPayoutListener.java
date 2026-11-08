package org.tornotron.echno_backend.modules.workprogress.billing.payment.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Hands a verified provider event to the applier on another thread, so the provider's delivery never
 * waits on a database lock. Kept apart from the applier because the {@code @WithoutTenant} declaration
 * it needs is made by an aspect around its own method, which an {@code @Async} method here would run on
 * the caller's thread.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillPayoutListener {

    private final BillPayoutApplier applier;

    @Async
    @EventListener
    public void onReceived(BillPayoutReceived received) {
        try {
            applier.apply(received);
        } catch (RuntimeException e) {
            log.error("Bill payment {}: could not apply the provider's answer: {}", received.paymentId(), e.getMessage(), e);
        }
    }
}
