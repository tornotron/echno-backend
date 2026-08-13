package org.tornotron.echno_backend.inspection.dtos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.tornotron.echno_backend.inspection.InspectionResult;
import org.tornotron.echno_backend.inspection.InspectionStatus;
import org.tornotron.echno_backend.inspection.InspectionType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Full replacement of an inspection. Status is set directly and the result is
 * optional (set once the inspection is concluded). The check items and defects
 * are rebuilt from the payload and the summary counts recomputed from them.
 */
public record UpdateInspectionRequest(
        @NotBlank @Size(max = 200) String title,
        @NotNull InspectionType type,
        @NotNull InspectionStatus status,
        InspectionResult result,
        Long projectId,
        @Size(max = 300) String location,
        @Size(max = 300) String areaInspected,
        @Size(max = 200) String drawingReference,
        @NotNull LocalDate scheduledDate,
        @Size(max = 20) String scheduledTime,
        LocalDateTime actualStartTime,
        LocalDateTime actualEndTime,
        @PositiveOrZero Integer duration,
        @NotNull Long inspectorId,
        Long contractorId,
        @Size(max = 200) String clientRepresentative,
        List<@Size(max = 200) String> attendees,
        @Size(max = 200) String weatherConditions,
        @Size(max = 50) String temperature,
        @Valid List<InspectionCheckItemRequest> checkItems,
        @Valid List<InspectionDefectRequest> defects
) {}
