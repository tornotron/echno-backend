package org.tornotron.echno_backend.inspection.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.inspection.DefectAnnotationShape;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "A mark drawn over one defect photo. Coordinates are fractions of the image "
        + "width and height, so the mark holds its place at any rendered size.")
public record DefectPhotoAnnotationDto(
        UUID id,
        UUID inspectionId,
        String photo,
        DefectAnnotationShape shape,
        BigDecimal x1,
        BigDecimal y1,
        BigDecimal x2,
        BigDecimal y2,
        @Schema(description = "Caption printed against the mark. Null where the mark was drawn "
                + "without one.", nullable = true)
        String label,
        int lineOrder,
        @Schema(description = "Employee who drew the mark, resolved from the signed-in user's "
                + "employee record in the current organization. Null where that user has no "
                + "employee record.", nullable = true)
        Long createdById
) {}
