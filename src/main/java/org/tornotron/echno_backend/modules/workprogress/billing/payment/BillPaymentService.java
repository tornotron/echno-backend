package org.tornotron.echno_backend.modules.workprogress.billing.payment;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.dto.BillPaymentsDto;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.dto.RaiseBillPaymentRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.dto.RecordBillPaymentRequest;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.BillPaymentGatewayException;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.BillPaymentGateways;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.PayoutResult;
import org.tornotron.echno_backend.user.UserContextService;

/**
 * Paying an approved bill: raise, authorize, then either a person records the bank reference or a
 * provider carries it. Deliberately not transactional: {@link BillPaymentStore} commits the signature
 * before the provider is called and commits the answer after, so the row always says what is known.
 *
 * <p>A call with no usable answer leaves the payment {@code AUTHORIZED} with the error on it. Calling
 * {@link #sync} sends it again with the same payment id as the idempotency key, so a request that did get
 * through cannot pay twice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillPaymentService {

    private final BillPaymentStore store;
    private final BillPaymentGateways gateways;
    private final UserContextService userContextService;

    public BillPaymentsDto list(UUID billId) {
        return store.view(billId);
    }

    public BillPaymentsDto raise(UUID billId, RaiseBillPaymentRequest req) {
        store.raise(billId, req, userContextService.getCurrentUserId());
        return store.view(billId);
    }

    public BillPaymentsDto authorize(UUID paymentId) {
        store.authorize(paymentId, userContextService.getCurrentUserId());
        BillPaymentStore.PaymentStep step = store.step(paymentId);
        if (step.provider() != BillPaymentProvider.MANUAL) {
            send(paymentId, step);
        }
        return store.view(step.billId());
    }

    public BillPaymentsDto reject(UUID paymentId, String reason) {
        store.reject(paymentId, reason, userContextService.getCurrentUserId());
        return store.view(store.step(paymentId).billId());
    }

    public BillPaymentsDto record(UUID paymentId, RecordBillPaymentRequest req) {
        store.recordManual(paymentId, req, userContextService.getCurrentUserId());
        return store.view(store.step(paymentId).billId());
    }

    /**
     * Sends again a payment whose send got no answer, or asks the provider where one stands, for when a
     * webhook was missed.
     *
     * @throws InvalidRequestException if a person, not a provider, finishes this payment
     */
    public BillPaymentsDto sync(UUID paymentId) {
        BillPaymentStore.PaymentStep step = store.step(paymentId);
        if (step.provider() == BillPaymentProvider.MANUAL) {
            throw new InvalidRequestException("This payment is manual; record the bank reference when it has been paid");
        }
        if (step.status() == BillPaymentStatus.AUTHORIZED) {
            send(paymentId, step);
        } else if (step.status() == BillPaymentStatus.PROCESSING) {
            fetch(paymentId, step);
        }
        return store.view(step.billId());
    }

    private void send(UUID paymentId, BillPaymentStore.PaymentStep step) {
        ask(paymentId, () -> gateways.require(step.provider(), TenantContext.getCurrentOrgId())
                .send(store.commandFor(paymentId)));
    }

    private void fetch(UUID paymentId, BillPaymentStore.PaymentStep step) {
        ask(paymentId, () -> gateways.require(step.provider(), TenantContext.getCurrentOrgId())
                .fetch(store.lookupFor(paymentId)));
    }

    private void ask(UUID paymentId, java.util.function.Supplier<PayoutResult> call) {
        PayoutResult result;
        try {
            result = call.get();
        } catch (BillPaymentGatewayException e) {
            log.warn("Bill payment {}: no usable answer from the provider", paymentId, e);
            store.noteProviderUnreachable(paymentId, e.getMessage());
            return;
        }
        store.applyOutcome(paymentId, result);
    }
}
