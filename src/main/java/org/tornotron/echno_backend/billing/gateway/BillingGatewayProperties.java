package org.tornotron.echno_backend.billing.gateway;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code echno.billing.*}: which provider is wired and the credentials for it. The keys are
 * deploy-time secrets held in the private deployment repository's vault and injected as
 * environment variables; see {@code docs/runbooks/payments-razorpay.md}. With
 * {@code provider=none}, or with {@code razorpay} but empty keys, the no-op gateway is wired
 * and the application behaves exactly as it did before payments existed.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "echno.billing")
public class BillingGatewayProperties {

    /** {@code none} or {@code razorpay}. */
    private String provider = "none";

    /** Currency every plan is billed in. */
    private String currency = "INR";

    /** The RBI additional-factor-of-authentication ceiling per debit, in paise. */
    private long afaCapPaise = MandatePolicy.DEFAULT_AFA_CAP_PAISE;

    private Razorpay razorpay = new Razorpay();

    public boolean isRazorpay() {
        return "razorpay".equalsIgnoreCase(provider == null ? "" : provider.trim());
    }

    @Getter
    @Setter
    public static class Razorpay {
        private String baseUrl = "https://api.razorpay.com/v1";
        private String keyId = "";
        private String keySecret = "";
        private String webhookSecret = "";
        private int connectTimeoutSeconds = 10;
        private int readTimeoutSeconds = 30;
        /** Optional forward proxy for egress; unset means direct. */
        private String proxyHost = "";
        private int proxyPort = 3128;

        public boolean hasApiKeys() {
            return notBlank(keyId) && notBlank(keySecret);
        }

        public boolean hasWebhookSecret() {
            return notBlank(webhookSecret);
        }

        private static boolean notBlank(String value) {
            return value != null && !value.isBlank();
        }
    }
}
