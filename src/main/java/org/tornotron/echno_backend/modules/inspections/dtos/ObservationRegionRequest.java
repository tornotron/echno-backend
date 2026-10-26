package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.tornotron.echno_backend.modules.inspections.RegionShape;

@Schema(description = "A box or polygon on one image already in the Echno store. Coordinates are fractions "
        + "of the image's width and height in [0, 1], rounded to 6 places, origin top left, x to the right, "
        + "y down, measured on the image as displayed (after EXIF orientation).")
public record ObservationRegionRequest(
        @Schema(description = "The image the region is drawn on. Must be in the caller's organisation.")
        @NotNull Long attachmentId,
        @NotNull RegionShape shape,
        @Schema(description = "BOX only: [x1, y1, x2, y2] with x1 < x2 and y1 < y2.", nullable = true)
        @Size(min = 4, max = 4) List<BigDecimal> box,
        @Schema(description = "POLYGON only: 3 to 200 [x, y] vertices, open (a closing repeat of the first "
                + "vertex is dropped), not crossing or touching itself.", nullable = true)
        @Size(min = 3, max = 201) List<List<BigDecimal>> points,
        @NotBlank @Size(max = 50) String classKey,
        @Schema(description = "The producer's score in [0, 1]. Not accepted on a region a person draws.",
                nullable = true)
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal score,
        @Schema(description = "Pixel width of the image as displayed, when known.", nullable = true)
        @Positive Integer imageWidth,
        @Schema(description = "Pixel height of the image as displayed, when known.", nullable = true)
        @Positive Integer imageHeight
) {}
