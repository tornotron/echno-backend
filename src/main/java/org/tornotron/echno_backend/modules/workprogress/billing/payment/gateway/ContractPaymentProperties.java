package org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.billing.gateway.BillingGatewayProperties;

/**
 * {@code echno.contract-payments.*}: what carries a bill payment, separate from
 * {@code echno.billing.*}, which collects subscriptions into the platform's own account. The keys here
 * are for the payout product on the paying organization's account, so they are never the same ones. See
 * {@code docs/runbooks/payments-razorpay.md}.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "echno.contract-payments")
public class ContractPaymentProperties {

    /** {@code none} (manual), {@code fake} (test only) or {@code razorpay}. */
    private String provider = "none";

    /** The RazorpayX account the money is paid from. */
    private String accountNumber = "";

    /**
     * The organizations paid through Razorpay. The keys and the account are per deployment while
     * organizations are not, so an organization not listed stays manual and cannot draw on another's
     * account.
     */
    private List<Long> organizationIds = new ArrayList<>();

    private BillingGatewayProperties.Razorpay razorpay = new BillingGatewayProperties.Razorpay();

    public boolean isFake() {
        return "fake".equalsIgnoreCase(provider == null ? "" : provider.trim());
    }

    public boolean isRazorpay() {
        return "razorpay".equalsIgnoreCase(provider == null ? "" : provider.trim());
    }

    /** Whether everything a live payout needs is here, the webhook secret included: it is how outcomes arrive. */
    public boolean isRazorpayComplete() {
        return razorpay.hasApiKeys() && razorpay.hasWebhookSecret() && accountNumber != null && !accountNumber.isBlank();
    }
}
