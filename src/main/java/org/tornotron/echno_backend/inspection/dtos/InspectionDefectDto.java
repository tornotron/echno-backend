package org.tornotron.echno_backend.inspection.dtos;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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
