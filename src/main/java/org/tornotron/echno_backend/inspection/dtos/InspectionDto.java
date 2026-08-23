package org.tornotron.echno_backend.inspection.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.compliance.CompliancePhase;
import org.tornotron.echno_backend.inspection.ComplianceRiskLevel;
import org.tornotron.echno_backend.inspection.InspectionOrigin;
import org.tornotron.echno_backend.inspection.InspectionResult;
import org.tornotron.echno_backend.inspection.InspectionStatus;
import org.tornotron.echno_backend.inspection.InspectionType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "An inspection as returned by the API, including its checklist items, defects and summary counts.")
public record InspectionDto(
        UUID id,
        String inspectionNumber,
        String title,
        InspectionType type,
        InspectionStatus status,
        InspectionResult result,
        Long projectId,
        String location,
        String areaInspected,
        String drawingReference,
        LocalDate scheduledDate,
        String scheduledTime,
        LocalDateTime actualStartTime,
        LocalDateTime actualEndTime,
        Integer duration,
        Long inspectorId,
        Long contractorId,
        String clientRepresentative,
        List<String> attendees,
        String weatherConditions,
        String temperature,
        int totalCheckPoints,
        int passedCheckPoints,
        int failedCheckPoints,
        int defectsFound,
        InspectionOrigin origin,
        CompliancePhase compliancePhase,
        ComplianceRiskLevel riskLevel,
        String resolutionOptions,
        String complianceRuleRef,
        String aiRationale,
        List<InspectionCheckItemDto> checkItems,
        List<InspectionDefectDto> defects,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
