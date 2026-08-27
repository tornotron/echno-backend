package org.tornotron.echno_backend.inspection.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.inspection.DefectSeverity;
import org.tornotron.echno_backend.inspection.NcrStatus;
import org.tornotron.echno_backend.inspection.NcrType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "A non-conformance report as returned by the API, with its assignment and "
        + "closure trail.")
public record NcrDto(
        UUID id,
        String ncrNumber,
        NcrType type,
        UUID inspectionId,
        UUID defectId,
        String title,
        String description,
        DefectSeverity severity,
        NcrStatus status,
        Long siteEngineerId,
        LocalDate targetDate,
        Long raisedById,
        Long closedById,
        String correctiveActionRemarks,
        String verificationRemarks,
        LocalDateTime correctiveActionCompletedAt,
        LocalDateTime verifiedAt,
        LocalDateTime closedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
