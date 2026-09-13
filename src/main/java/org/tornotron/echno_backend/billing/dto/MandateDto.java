package org.tornotron.echno_backend.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;
import org.tornotron.echno_backend.billing.gateway.MandateMethod;
import org.tornotron.echno_backend.billing.gateway.NormalizedMandateStatus;

import java.time.Instant;

@Schema(description = "A recurring-payment mandate the organization has registered with the provider.")
@Value
@Builder
public class MandateDto {

    @Schema(description = "Numeric id.", example = "3")
    Long id;

    @Schema(description = "The provider; NONE never occurs for a registered mandate.", example = "RAZORPAY")
    String provider;

    @Schema(description = "The provider's mandate or token reference.", example = "token_Nx8k4StV2cAd3e")
    String providerMandateRef;

    @Schema(description = "The provider subscription the mandate was registered for, when known.", example = "sub_Nx8k2QhT0aYb1c")
    String providerSubscriptionId;

    @Schema(description = "The instrument the mandate is on.", example = "UPI_AUTOPAY")
    MandateMethod method;

    @Schema(description = "Lifecycle state.", example = "AUTHORIZED")
    NormalizedMandateStatus status;

    @Schema(description = "Ceiling per debit, in paise.", example = "999900")
    Long maxAmountPaise;

    Instant authorizedAt;
    Instant revokedAt;
    Instant createdAt;
}
