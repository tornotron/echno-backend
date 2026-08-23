package org.tornotron.echno_backend.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "Payload to record usage of a metered feature.")
@Data
public class UsageRecordDto {

    @Schema(description = "Code of the metered feature being used.", example = "storage-gb")
    @NotBlank(message = "Feature code is required")
    private String featureCode;

    @Schema(description = "Amount of usage to add to the current period's total.", example = "5")
    @NotNull(message = "Amount is required")
    @Min(value = 1, message = "Amount must be at least 1")
    private Long amount;
}
