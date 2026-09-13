package org.tornotron.echno_backend.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "The success payload the provider's browser widget returned, for server-side signature verification.")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerifyCheckoutDto {

    @Schema(description = "The provider payment id.", example = "pay_Nx8k3RtU1bZc2d")
    @NotBlank(message = "Provider payment id is required")
    private String providerPaymentId;

    @Schema(description = "The provider's signature over the payment and the subscription or order.")
    @NotBlank(message = "Provider signature is required")
    private String providerSignature;

    @Schema(description = "The provider subscription the payment authorized, for a recurring checkout.", example = "sub_Nx8k2QhT0aYb1c")
    private String providerSubscriptionId;

    @Schema(description = "The provider order the payment paid, for a one-off checkout.")
    private String providerOrderId;
}
