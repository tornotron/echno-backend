package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.tornotron.echno_backend.modules.inspections.RegionEditKind;
import org.tornotron.echno_backend.modules.inspections.RegionShape;

@Schema(description = "One reviewer operation on the observation's regions. replace reads regionId, shape, "
        + "box or points, and classKey; it retires the row and inserts the correction with the same shape on "
        + "the same image. remove reads regionId and retires the row. add reads attachmentId, shape, box or "
        + "points, and classKey; the image must already be cited by the observation (a region, an evidenceRefs "
        + "attachmentId, or its own evidence).")
public record RegionEditOp(
        @NotNull RegionEditKind op,
        @Schema(description = "The active region to replace or remove.", nullable = true)
        UUID regionId,
        @Schema(description = "add only: the image to draw on.", nullable = true)
        Long attachmentId,
        @Schema(nullable = true) RegionShape shape,
        @Schema(description = "BOX: [x1, y1, x2, y2].", nullable = true)
        @Size(min = 4, max = 4) List<BigDecimal> box,
        @Schema(description = "POLYGON: [[x, y], ...].", nullable = true)
        @Size(min = 3, max = 201) List<List<BigDecimal>> points,
        @Schema(nullable = true) @Size(max = 50) String classKey,
        @Schema(description = "add only: pixel width of the image as displayed.", nullable = true)
        @Positive Integer imageWidth,
        @Schema(description = "add only: pixel height of the image as displayed.", nullable = true)
        @Positive Integer imageHeight
) {}
