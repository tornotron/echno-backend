package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.modules.inspections.compliance.CompliancePhase;
import org.tornotron.echno_backend.modules.inspections.ComplianceRiskLevel;
import org.tornotron.echno_backend.modules.inspections.InspectionCategory;
import org.tornotron.echno_backend.modules.inspections.InspectionOrigin;
import org.tornotron.echno_backend.modules.inspections.InspectionResult;
import org.tornotron.echno_backend.modules.inspections.InspectionStatus;
import org.tornotron.echno_backend.modules.inspections.InspectionType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.tornotron.echno_backend.project.spatial.dto.SpatialPathSegment;
import java.util.UUID;

@Schema(description = "An inspection as returned by the API, including its checklist items, defects and summary counts.")
public record InspectionDto(
        UUID id,
        String inspectionNumber,
        String title,
        InspectionType type,
        InspectionCategory category,
        @Schema(description = "QA/QC stage or trade the inspection covers, as its slug. Null on safety and "
                + "compliance inspections, which carry no trade, and null on a QA/QC inspection "
                + "whose payload omitted it.", nullable = true, example = "reinforcement")
        String trade,
        @Schema(description = "Id of the organization's trade row.", nullable = true)
        UUID tradeId,
        @Schema(nullable = true) String tradeName,
        @Schema(description = "Group code of the trade.", nullable = true) String tradeGroup,
        InspectionStatus status,
        @Schema(description = "Overall outcome of the inspection. Null until the inspection is "
                + "concluded and a result is recorded against it.", nullable = true)
        InspectionResult result,
        @Schema(description = "Project the inspection is against. The column permits null and the "
                + "create payload does not require the field, so an inspection can be recorded "
                + "without a project.", nullable = true)
        Long projectId,
        @Schema(description = "Site or building location of the inspection. Null where none was "
                + "recorded.", nullable = true)
        String location,
        @Schema(description = "Specific area inspected within the location. Null where none was "
                + "recorded.", nullable = true)
        String areaInspected,
        @Schema(description = "Drawing the inspection was carried out against. Null where none was "
                + "recorded.", nullable = true)
        String drawingReference,
        @Schema(description = "Date the inspection is scheduled for. Every payload the API accepts "
                + "requires one, but AI-generated compliance inspections are written by the "
                + "generation service without a schedule, so those carry none until a project "
                + "manager fills it in.", nullable = true)
        LocalDate scheduledDate,
        @Schema(description = "Scheduled time of day. Null where only a date was given.",
                nullable = true)
        String scheduledTime,
        @Schema(description = "When the inspection actually started. Null until it is carried out.",
                nullable = true)
        LocalDateTime actualStartTime,
        @Schema(description = "When the inspection actually finished. Null until it is carried out.",
                nullable = true)
        LocalDateTime actualEndTime,
        @Schema(description = "Duration of the inspection in minutes, stored as sent on the payload "
                + "and never derived from the start and end times. Null where the payload carried "
                + "none.", nullable = true)
        Integer duration,
        @Schema(description = "Employee performing the inspection. Every payload the API accepts "
                + "requires one, but AI-generated compliance inspections are written by the "
                + "generation service without an inspector, so those carry none until a project "
                + "manager assigns one.", nullable = true)
        Long inspectorId,
        @Schema(description = "Contractor whose work is being inspected. Null where the inspection "
                + "is not against a contractor's work.", nullable = true)
        Long contractorId,
        @Schema(description = "Name of the client's representative present at the inspection. Null "
                + "where none was recorded.", nullable = true)
        String clientRepresentative,
        List<String> attendees,
        @Schema(description = "Weather at the time of the inspection. Null where none was "
                + "recorded.", nullable = true)
        String weatherConditions,
        @Schema(description = "Ambient temperature at the time of the inspection. Null where none "
                + "was recorded.", nullable = true)
        String temperature,
        int totalCheckPoints,
        int passedCheckPoints,
        int failedCheckPoints,
        int defectsFound,
        InspectionOrigin origin,
        @Schema(description = "Project phase the compliance rule applies to. Written only by the "
                + "compliance generation service, so null on every manually created inspection.",
                nullable = true)
        CompliancePhase compliancePhase,
        @Schema(description = "Risk the compliance rule carries, taken from the model's suggestion "
                + "and falling back to the rule's own default. Written only by the compliance "
                + "generation service, so null on every manually created inspection, and null "
                + "again where the rule itself carries no default.", nullable = true)
        ComplianceRiskLevel riskLevel,
        @Schema(description = "Newline-separated ways of satisfying the compliance rule, taken from "
                + "the model's suggestion and falling back to the rule's own. Written only by the "
                + "compliance generation service, so null on every manually created inspection, "
                + "and null again where the rule itself lists none.", nullable = true)
        String resolutionOptions,
        @Schema(description = "Code of the compliance rule that produced this inspection. Written "
                + "only by the compliance generation service, so null on every manually created "
                + "inspection.", nullable = true)
        String complianceRuleRef,
        @Schema(description = "The model's reasoning for suggesting this compliance inspection. "
                + "Written only by the compliance generation service, so null on every manually "
                + "created inspection.", nullable = true)
        String aiRationale,
        List<InspectionCheckItemDto> checkItems,
        List<InspectionDefectDto> defects,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        @Schema(description = "Site structure node the inspection covers, or null where only the free-text "
                + "location was recorded.", nullable = true)
        UUID spatialNodeId,
        @Schema(description = "Ordered ancestors from the building down to spatialNodeId, for a "
                + "breadcrumb with no second call. Empty when spatialNodeId is null.")
        List<SpatialPathSegment> spatialPath
) {

    /** The same record with its check items and defects replaced, for filling in their breadcrumbs. */
    public InspectionDto withChildren(List<InspectionCheckItemDto> items, List<InspectionDefectDto> defectList) {
        return new InspectionDto(
                id, inspectionNumber, title, type, category, trade, status, result, projectId, location,
                areaInspected, drawingReference, scheduledDate, scheduledTime,
                actualStartTime, actualEndTime, duration, inspectorId, contractorId,
                clientRepresentative, attendees, weatherConditions, temperature,
                totalCheckPoints, passedCheckPoints, failedCheckPoints, defectsFound,
                origin, compliancePhase, riskLevel, resolutionOptions,
                complianceRuleRef, aiRationale, items, defectList, createdAt,
                updatedAt, spatialNodeId, spatialPath);
    }

    /** The same record with the breadcrumb filled in; the mapper leaves it empty. */
    public InspectionDto withSpatialPath(List<SpatialPathSegment> path) {
        return new InspectionDto(
                id, inspectionNumber, title, type, category, trade, status, result, projectId, location,
                areaInspected, drawingReference, scheduledDate, scheduledTime,
                actualStartTime, actualEndTime, duration, inspectorId, contractorId,
                clientRepresentative, attendees, weatherConditions, temperature,
                totalCheckPoints, passedCheckPoints, failedCheckPoints, defectsFound,
                origin, compliancePhase, riskLevel, resolutionOptions,
                complianceRuleRef, aiRationale, checkItems, defects, createdAt,
                updatedAt, spatialNodeId, path);
    }
}
