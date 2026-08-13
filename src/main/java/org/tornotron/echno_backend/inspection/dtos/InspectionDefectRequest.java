package org.tornotron.echno_backend.inspection.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * Defect payload shared by the create and update requests. The number of defects
 * recorded feeds the inspection's {@code defectsFound} count, computed server-side.
 */
public record InspectionDefectRequest(
        @Size(max = 200) String category,
        @NotBlank @Size(max = 1000) String description,
        @Size(max = 20) String severity,
        @Size(max = 300) String location,
        List<@Size(max = 500) String> photos,
        @NotBlank @Size(max = 1000) String correctiveAction,
        @Size(max = 200) String responsibleParty,
        LocalDate targetDate,
        @Size(max = 20) String status,
        LocalDate resolvedDate
) {}
