package org.tornotron.echno_backend.inspection.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.tornotron.echno_backend.inspection.CheckItemStatus;

import java.util.List;

/**
 * Check-point payload shared by the create and update requests. The inspection's
 * passed, failed and total counts are derived server-side from the supplied
 * check items; the client does not set them.
 */
public record InspectionCheckItemRequest(
        @NotBlank @Size(max = 200) String category,
        @NotBlank @Size(max = 500) String checkPoint,
        @Size(max = 1000) String specification,
        @NotNull CheckItemStatus status,
        @Size(max = 1000) String remarks,
        boolean photosRequired,
        List<@Size(max = 500) String> photos,
        @Size(max = 200) String measurement,
        @Size(max = 200) String expectedValue,
        @Size(max = 20) String priority
) {}
