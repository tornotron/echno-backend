package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.modules.inspections.DefectSeverity;
import org.tornotron.echno_backend.modules.inspections.NcrStatus;
import org.tornotron.echno_backend.modules.inspections.NcrType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "A non-conformance report as returned by the API, with its assignment and "
        + "closure trail, and the inspection and project it traces back to.")
public record NcrDto(
        UUID id,
        String ncrNumber,
        NcrType type,
        UUID inspectionId,
        @Schema(description = "Inspection defect the report was raised from. Null where the report "
                + "was raised against the inspection as a whole rather than one defect.",
                nullable = true)
        UUID defectId,
        String title,
        String description,
        @Schema(description = "How serious the non-conformance is. Null where none was recorded "
                + "when the report was raised.", nullable = true)
        DefectSeverity severity,
        NcrStatus status,
        @Schema(description = "Site engineer the report is assigned to. Null until the report is "
                + "assigned.", nullable = true)
        Long siteEngineerId,
        @Schema(description = "Date the corrective action is due. Null until one is set, which "
                + "normally happens when the report is assigned.", nullable = true)
        LocalDate targetDate,
        @Schema(description = "Employee who raised the report, resolved from the signed-in user's "
                + "employee record in the current organization. Null where that user has no "
                + "employee record.", nullable = true)
        Long raisedById,
        @Schema(description = "Employee who accepted or refused the corrective work on "
                + "re-inspection. Null until re-inspection, and null again where the verifying "
                + "user has no employee record in the current organization.", nullable = true)
        Long verifiedById,
        @Schema(description = "Employee who closed the report. Null until the report is closed, "
                + "cleared again when a closed report is reopened, and null where the closing user "
                + "has no employee record in the current organization.", nullable = true)
        Long closedById,
        @Schema(description = "What was done to correct the non-conformance. Null until the "
                + "corrective action is marked complete.", nullable = true)
        String correctiveActionRemarks,
        @Schema(description = "Remarks recorded at re-inspection. Null until the report is "
                + "verified, rejected or reopened.", nullable = true)
        String verificationRemarks,
        @Schema(description = "When the corrective action was marked complete. Null until it is.",
                nullable = true)
        LocalDateTime correctiveActionCompletedAt,
        @Schema(description = "When the corrective work was accepted or refused on re-inspection. "
                + "Null until re-inspection.", nullable = true)
        LocalDateTime verifiedAt,
        @Schema(description = "When the report was closed. Null until it is closed, and cleared "
                + "again when a closed report is reopened.", nullable = true)
        LocalDateTime closedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        @Schema(description = "Observation behind the non-conformance: the defect's when raised from a "
                + "defect, otherwise its own. Null on reports raised before observations existed.",
                nullable = true)
        UUID observationId,
        @Schema(description = "Document number of the inspection the report was raised against. "
                + "Resolved through the inspection; null only where that inspection is no longer "
                + "readable.", nullable = true)
        String inspectionNumber,
        @Schema(description = "Title of the inspection the report was raised against. Resolved "
                + "through the inspection; null only where that inspection is no longer readable.",
                nullable = true)
        String inspectionTitle,
        @Schema(description = "Project the report belongs to: the project of its inspection, never "
                + "a value the client sent. Null where the inspection was recorded without a "
                + "project.", nullable = true)
        Long projectId,
        @Schema(description = "Display name of that project. Null where projectId is null.",
                nullable = true)
        String projectName
) {

    /**
     * The same report with its inspection and project filled in; the mapper leaves them null.
     *
     * @param inspection The inspection the report was raised against, or null where it could not
     *                   be read, in which case the four fields stay null.
     * @param projectName The name of that inspection's project, or null.
     * @return The report with its trace filled in.
     */
    public NcrDto withInspection(InspectionReference inspection, String projectName) {
        if (inspection == null) {
            return this;
        }
        return new NcrDto(id, ncrNumber, type, inspectionId, defectId, title, description, severity,
                status, siteEngineerId, targetDate, raisedById, verifiedById, closedById,
                correctiveActionRemarks, verificationRemarks, correctiveActionCompletedAt,
                verifiedAt, closedAt, createdAt, updatedAt, observationId,
                inspection.inspectionNumber(), inspection.title(), inspection.projectId(),
                projectName);
    }
}
