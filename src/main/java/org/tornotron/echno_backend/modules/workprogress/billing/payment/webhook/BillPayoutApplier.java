package org.tornotron.echno_backend.modules.workprogress.billing.payment.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.common.multitenancy.WithoutTenant;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.BillPaymentStore;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.ContractBillPaymentRepository;

/**
 * Applies a verified provider event to its payment. The event arrives with no session, so it works out
 * the organization from the payment the signed event names and pins the tenant to that for the write.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillPayoutApplier {

    private final ContractBillPaymentRepository payments;
    private final BillPaymentStore store;
    private final TenantScopedJobRunner tenantRunner;

    @WithoutTenant("A provider event arrives with no session; the organization comes from the payment it names")
    public void apply(BillPayoutReceived received) {
        Long orgId = payments.findOrganizationId(received.paymentId()).orElse(null);
        if (orgId == null) {
            log.warn("Bill payout webhook names payment {}, which does not exist; dropped", received.paymentId());
            return;
        }
        tenantRunner.runForTenant(orgId, () -> store.applyOutcome(received.paymentId(), received.result()));
    }
}
