package org.tornotron.echno_backend.asset.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "One entry in an asset's movement ledger: what moved, from where to where, "
        + "when, by whom and why. Entries are never edited or deleted; an entry made in error is "
        + "superseded by a CORRECTION that names it.")
@Data
public class AssetMovementDto {

    @Schema(description = "Database id of the ledger entry.", example = "412")
    private Long id;

    @Schema(description = "Id of the asset that moved.", example = "12")
    private Long assetId;

    @Schema(description = "Kind of entry: REGISTRATION, TRANSFER, ASSIGNMENT or CORRECTION.",
            example = "TRANSFER")
    private String movementType;

    @Schema(description = "Id of the project the asset left, null if it was on none.", example = "3", nullable = true)
    private Long fromProjectId;
    @Schema(description = "Name of the project the asset left, as it read at the time.",
            example = "Central Yard")
    private String fromProjectName;
    @Schema(description = "Id of the project the asset moved to, null if it is on none.", example = "5", nullable = true)
    private Long toProjectId;
    @Schema(description = "Name of the project the asset moved to, as it read at the time.",
            example = "Silver Oak Residences")
    private String toProjectName;

    @Schema(description = "Id of the storage location the asset left.", example = "7")
    private Long fromLocationId;
    @Schema(description = "Name of the storage location the asset left, as it read at the time.",
            example = "Kochi Yard")
    private String fromLocationName;
    @Schema(description = "Id of the storage location the asset moved to.", example = "9")
    private Long toLocationId;
    @Schema(description = "Name of the storage location the asset moved to, as it read at the time.",
            example = "Silver Oak Site Store")
    private String toLocationName;

    @Schema(description = "Id of the custodian who handed the asset over.", example = "18")
    private Long fromAssignedToId;
    @Schema(description = "Name of the custodian who handed the asset over.", example = "Ravi Kumar")
    private String fromAssignedTo;
    @Schema(description = "Id of the custodian who took the asset on.", example = "21")
    private Long toAssignedToId;
    @Schema(description = "Name of the custodian who took the asset on.", example = "Suresh Nair")
    private String toAssignedTo;

    @Schema(description = "When the asset actually moved.", example = "2026-08-20T09:00:00")
    private LocalDateTime movedAt;

    @Schema(description = "When the entry was written, which can be later than movedAt for a "
            + "movement entered after the fact.", example = "2026-08-21T11:42:03")
    private LocalDateTime recordedAt;

    @Schema(description = "Id of the user who recorded the movement.", example = "4")
    private Long movedBy;

    @Schema(description = "Why the asset moved.",
            example = "Mobilised to the Marina Heights site for the piling phase")
    private String reason;

    @Schema(description = "Free-text notes about the movement.",
            example = "Delivered on a low bed trailer, hydraulics checked on arrival.")
    private String notes;

    @Schema(description = "An external document this movement came from, for example a site "
            + "transfer number.", example = "TRF-2026-000014")
    private String referenceNumber;

    @Schema(description = "Id of the entry this one restates, set only on a CORRECTION.",
            example = "409")
    private Long correctsMovementId;
}
