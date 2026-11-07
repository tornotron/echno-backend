package org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code echno.contract-payments.*}: what carries a bill payment, separate from
 * {@code echno.billing.*}, which collects subscriptions. See the billing payments design note.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "echno.contract-payments")
public class ContractPaymentProperties {

    /** {@code none} (manual), {@code fake} (test only) or {@code razorpay}. */
    private String provider = "none";

    public boolean isFake() {
        return "fake".equalsIgnoreCase(provider == null ? "" : provider.trim());
    }
}
