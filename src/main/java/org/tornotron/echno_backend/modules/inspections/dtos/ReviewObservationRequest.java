package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.tornotron.echno_backend.modules.inspections.CheckItemStatus;
import org.tornotron.echno_backend.modules.inspections.DefectSeverity;
import org.tornotron.echno_backend.modules.inspections.ObservationOutcomeKind;
import org.tornotron.echno_backend.modules.inspections.ObservationReviewDecision;

import java.util.UUID;

@Schema(description = "The human decision on a pending observation. One decision per observation; "
        + "a second one is refused with 409.")
public record ReviewObservationRequest(
        @NotNull ObservationReviewDecision decision,
        @Schema(description = "Reviewer's note. Required on REJECT.", nullable = true)
        @Size(max = 4000) String note,
        @Schema(description = "The reviewer's edits, for MODIFY. The proposal columns are left as the "
                + "producer wrote them; the edits are stored as a diff.", nullable = true)
        @Valid Changes changes,
        @Schema(description = "What the observation becomes. Ignored on REJECT.", nullable = true)
        @Valid Outcome outcome
) {

    @Schema(description = "Fields the reviewer changed. Only the ones set are compared to the proposal.")
    public record Changes(
            @Size(max = 200) String title,
            @Size(max = 4000) String description,
            DefectSeverity severity,
            UUID spatialNodeId,
            @Size(max = 200) String category
    ) {}

    @Schema(description = "The outcome to link. kind decides which of the other fields are read: "
            + "CHECK_ITEM reads checkItemId and status; DEFECT reads defectId (attach) or defect "
            + "(create, needs the observation on an inspection); INSPECTION reads inspectionId. NCR is not "
            + "chosen here: an NCR raised through the NCR endpoint links its observation itself, and a "
            + "review naming it is refused with 400.")
    public record Outcome(
            @NotNull ObservationOutcomeKind kind,
            UUID checkItemId,
            CheckItemStatus status,
            UUID defectId,
            @Valid InspectionDefectRequest defect,
            UUID inspectionId
    ) {
        public static Outcome none() {
            return new Outcome(ObservationOutcomeKind.NONE, null, null, null, null, null);
        }
    }
}
