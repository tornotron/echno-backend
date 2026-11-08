package org.tornotron.echno_backend.modules.vision.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@Schema(description = "One class a model version outputs.")
public record CvModelClassDto(
        @Schema(description = "A key of the class taxonomy.")
        @NotBlank @Size(max = 50) String key,
        @Schema(description = "BOX or POLYGON; must be the shape the taxonomy gives the class.")
        @NotBlank @Size(max = 10) String shape,
        @Schema(description = "Operating threshold in [0, 1] chosen when the version was evaluated.")
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal threshold
) {}
