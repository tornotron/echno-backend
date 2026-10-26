package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.tornotron.echno_backend.modules.inspections.RegionOrigin;
import org.tornotron.echno_backend.modules.inspections.RegionShape;

@Schema(description = "A box or polygon on one image, as fractions of the image in [0, 1]. A reviewer's "
        + "correction is a new row that supersedes the producer's; the producer's row is retired, never edited.")
public record ObservationRegionDto(
        UUID id,
        Long attachmentId,
        RegionShape shape,
        @Schema(description = "[x1, y1, x2, y2]; the bounding box for a polygon.")
        List<BigDecimal> box,
        @Schema(description = "POLYGON only: open vertex list [[x, y], ...].", nullable = true)
        List<List<BigDecimal>> points,
        @Schema(nullable = true) Integer imageWidth,
        @Schema(nullable = true) Integer imageHeight,
        String classKey,
        @Schema(description = "Producer score in [0, 1]; null for a region a person drew.", nullable = true)
        BigDecimal score,
        RegionOrigin origin,
        @Schema(description = "The region this one corrects.", nullable = true)
        UUID supersedesId,
        @Schema(description = "Set when a review replaced or removed the region. Retired rows are returned "
                + "only on request.", nullable = true)
        LocalDateTime retiredAt,
        @Schema(nullable = true) Long retiredById,
        int lineOrder,
        LocalDateTime createdAt,
        @Schema(description = "Employee who drew it; null for a producer's region.", nullable = true)
        Long createdById
) {}
