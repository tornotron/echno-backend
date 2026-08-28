package org.tornotron.echno_backend.inspection.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.tornotron.echno_backend.inspection.DefectAnnotationShape;

import java.math.BigDecimal;

/**
 * One mark to draw over a defect photo.
 *
 * <p>The coordinates are fractions of the image, not pixels, and the bounds are
 * enforced here rather than left to the client: a value outside {@code [0, 1]}
 * describes a point off the image, which the renderer cannot draw and the reader
 * cannot interpret.
 */
@Schema(description = "A mark to draw over one defect photo, positioned as fractions of the image.")
public record DefectPhotoAnnotationRequest(
        @Schema(description = "The photo to draw on, exactly as it appears in the defect's photos list.",
                example = "https://cdn.example.com/inspections/6f1c-crack.jpg")
        @NotBlank @Size(max = 500) String photo,

        @Schema(description = "Shape of the mark.", example = "rectangle")
        @NotNull DefectAnnotationShape shape,

        @Schema(description = "First point, x, as a fraction of the image width. A corner for a box "
                + "shape, the tail for an arrow.", example = "0.31")
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal x1,

        @Schema(description = "First point, y, as a fraction of the image height.", example = "0.22")
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal y1,

        @Schema(description = "Second point, x. The opposite corner for a box shape, the head for an "
                + "arrow.", example = "0.58")
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal x2,

        @Schema(description = "Second point, y.", example = "0.47")
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal y2,

        @Schema(description = "What the mark points out, printed beside it on the report.",
                example = "Honeycombing, approx 120 mm across")
        @Size(max = 200) String label
) {}
