package org.tornotron.echno_backend.siteTransfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "Why a transfer that never arrived is being abandoned. Cancelling returns "
        + "the stock to the sending project and location it was drawn from, which is a real "
        + "movement on the ledger, so the reason is required and is kept on the transfer's status "
        + "trail beside the movement it caused.")
@Data
public class SiteTransferCancellationDto {

    @Schema(description = "Why the transfer is being cancelled.",
            example = "Lorry turned back at the gate, materials never left the yard")
    @NotBlank(message = "cancellation reason is required")
    @Size(max = 500, message = "cancellation reason cannot exceed 500 characters")
    private String reason;
}
