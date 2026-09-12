package org.tornotron.echno_backend.modules.inspections.dtos;

import org.tornotron.echno_backend.modules.inspections.ReinspectionOutcome;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record ReinspectionDto(UUID id,
                              Long projectId,
                              UUID ncrId,
                              UUID defectId,
                              UUID originalInspectionId,
                              UUID reinspectionInspectionId,
                              int sequence,
                              Long requestedById,
                              LocalDateTime requestedAt,
                              Long assignedInspectorId,
                              LocalDate targetDate,
                              ReinspectionOutcome outcome,
                              Long outcomeById,
                              LocalDateTime outcomeAt,
                              String remarks,
                              LocalDateTime createdAt,
                              LocalDateTime updatedAt) {
}
