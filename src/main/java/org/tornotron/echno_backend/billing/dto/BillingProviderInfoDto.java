package org.tornotron.echno_backend.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

@Schema(description = "Which payment provider the environment is wired to, and what the browser checkout needs to start.")
@Value
@Builder
public class BillingProviderInfoDto {

    @Schema(description = "The provider; NONE when online checkout is unavailable.", example = "RAZORPAY")
    String provider;

    @Schema(description = "Whether online checkout can be started. False under NONE or when the keys are missing.", example = "true")
    boolean enabled;

    @Schema(description = "The public key id the browser checkout is initialised with; null when not configured. Never the secret.", example = "rzp_test_Ab12Cd34Ef56Gh")
    String keyId;

    @Schema(description = "Currency every plan is billed in.", example = "INR")
    String currency;

    @Schema(description = "The RBI additional-factor-authentication ceiling per recurring debit, in paise.", example = "1500000")
    long afaCapPaise;

    @Schema(description = "Hours of notice the buyer gets before each recurring debit.", example = "24")
    int preDebitNoticeHours;

    @Schema(description = "The checkout flows the provider supports.", example = "[\"SUBSCRIPTION\"]")
    java.util.List<String> supportedFlows;
}
