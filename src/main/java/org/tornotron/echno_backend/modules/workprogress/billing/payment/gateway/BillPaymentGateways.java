package org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway;

import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.billing.gateway.razorpay.RazorpayRestClient;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.BillPaymentProvider;

/**
 * Says what carries bill payments for an organization. An empty answer is manual: a person pays from
 * the bank and records the reference, and nothing calls out. A misconfigured provider logs a warning and
 * degrades to manual, so a deployment can never half-work with money.
 */
@Slf4j
@Component
public class BillPaymentGateways {

    private final BillPaymentGateway wired;
    /** Null means every organization. */
    private final Set<Long> organizations;

    public BillPaymentGateways(ContractPaymentProperties properties) {
        if (properties.isFake()) {
            log.warn("Bill payments: the fake provider is wired. No money moves; never set this on a live deployment");
            wired = new FakeBillPaymentGateway();
            organizations = null;
        } else if (properties.isRazorpay() && properties.isRazorpayComplete()) {
            log.info("Bill payments: Razorpay payouts for organizations {}", properties.getOrganizationIds());
            wired = new RazorpayPayoutGateway(new RazorpayRestClient(properties.getRazorpay()),
                    properties.getAccountNumber().trim());
            organizations = Set.copyOf(properties.getOrganizationIds());
        } else {
            if (properties.isRazorpay()) {
                log.warn("Bill payments: echno.contract-payments.provider=razorpay but its keys, account number or "
                        + "webhook secret are empty; falling back to manual");
            } else {
                log.info("Bill payments: manual, a person pays from the bank and records the reference");
            }
            wired = null;
            organizations = null;
        }
    }

    /** The gateway serving this organization, or empty for manual. */
    public Optional<BillPaymentGateway> forOrganization(Long organizationId) {
        if (wired == null || (organizations != null && !organizations.contains(organizationId))) {
            return Optional.empty();
        }
        return Optional.of(wired);
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
