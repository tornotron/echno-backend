package org.tornotron.echno_backend.inspection.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "A defect identified during an inspection, as returned by the API.")
public record InspectionDefectDto(
        UUID id,
        String category,
        String description,
        String severity,
        String location,
        List<String> photos,
        String correctiveAction,
        String responsibleParty,
        LocalDate targetDate,
        String status,
        LocalDate resolvedDate
) {}
