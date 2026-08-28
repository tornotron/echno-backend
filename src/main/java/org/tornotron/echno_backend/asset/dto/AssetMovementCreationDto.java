package org.tornotron.echno_backend.asset.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "Payload to move an asset: where it is going, when it went, and why. "
        + "Appends an entry to the asset's movement ledger and brings the asset's current "
        + "placement with it. Nothing else can change where an asset is.")
@Data
public class AssetMovementCreationDto {

    @Schema(description = "Id of the project the asset is moving to. Omit, or send null, to take "
            + "the asset off any project.", example = "5")
    private Long toProjectId;

    @Schema(description = "Id of the storage location the asset is moving to. Omit, or send null, "
            + "to record that it sits at no tracked location.", example = "9")
    private Long toLocationId;

    @Schema(description = "Id of the custodian taking the asset on.", example = "21")
    private Long toAssignedToId;

    @Schema(description = "Name of the custodian taking the asset on.", example = "Suresh Nair")
    @Size(max = 200)
    private String toAssignedTo;

    @Schema(description = "Why the asset moved. Required: an entry with no stated reason is what "
            + "makes a ledger unexplainable.",
            example = "Mobilised to the Marina Heights site for the piling phase")
    @NotBlank
    @Size(max = 255)
    private String reason;

    @Schema(description = "When the asset actually moved. Defaults to now, and may be backdated "
            + "for a movement entered after the fact. A date in the future is refused.",
            example = "2026-08-20T09:00:00")
    private LocalDateTime movedAt;

    @Schema(description = "Free-text notes about the movement.",
            example = "Delivered on a low bed trailer, hydraulics checked on arrival.")
    private String notes;

    @Schema(description = "An external document this movement came from, for example a site "
            + "transfer number.", example = "TRF-2026-000014")
    @Size(max = 100)
    private String referenceNumber;

    @Schema(description = "Id of an earlier entry on this asset that this one restates. Setting it "
            + "records the entry as a CORRECTION, which is how a ledger mistake is put right: the "
            + "wrong entry stays, and this one supersedes it.", example = "409")
    private Long correctsMovementId;
}
