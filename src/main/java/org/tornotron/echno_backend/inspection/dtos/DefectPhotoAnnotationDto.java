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
        String label,
        int lineOrder,
        Long createdById
) {}
