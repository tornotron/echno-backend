package org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway;

import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.BillPaymentProvider;

/**
 * Says what carries bill payments for an organization. An empty answer is manual: a person pays from
 * the bank and records the reference, and nothing calls out.
 */
@Slf4j
@Component
public class BillPaymentGateways {

    private final BillPaymentGateway wired;

    public BillPaymentGateways(ContractPaymentProperties properties) {
        if (properties.isFake()) {
            log.warn("Bill payments: the fake provider is wired. No money moves; never set this on a live deployment");
            wired = new FakeBillPaymentGateway();
        } else {
            log.info("Bill payments: manual, a person pays from the bank and records the reference");
            wired = null;
        }
    }

    /** The gateway serving this organization, or empty for manual. */
    public Optional<BillPaymentGateway> forOrganization(Long organizationId) {
        return Optional.ofNullable(wired);
    }

    public BillPaymentProvider providerFor(Long organizationId) {
        return forOrganization(organizationId).map(BillPaymentGateway::provider).orElse(BillPaymentProvider.MANUAL);
    }

    /**
     * The gateway for a payment already raised on this provider.
     *
     * @throws BillPaymentGatewayException if the deployment no longer has that provider wired
     */
    public BillPaymentGateway require(BillPaymentProvider provider, Long organizationId) {
        return forOrganization(organizationId).filter(gateway -> gateway.provider() == provider)
                .orElseThrow(() -> new BillPaymentGatewayException(
                        "This payment was raised on " + provider + ", which this deployment no longer has configured"));
    }
}
