package org.tornotron.echno_backend.asset.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "One stretch of time an asset spent in one place, worked out from "
        + "consecutive entries in its movement ledger. This is what answers \"14 days at Central "
        + "Yard, 45 days at Silver Oak\".")
@Data
public class AssetPlacementSpanDto {

    @Schema(description = "Id of the ledger entry that started this placement.", example = "412")
    private Long movementId;

    @Schema(description = "Id of the project the asset was on, null if it was on none.", example = "5")
    private Long projectId;
    @Schema(description = "Name of the project the asset was on, as it read at the time.",
            example = "Silver Oak Residences")
    private String projectName;

    @Schema(description = "Id of the storage location the asset sat at.", example = "9")
    private Long locationId;
    @Schema(description = "Name of the storage location the asset sat at, as it read at the time.",
            example = "Silver Oak Site Store")
    private String locationName;

    @Schema(description = "Id of the custodian who held the asset over this stretch.", example = "21")
    private Long assignedToId;
    @Schema(description = "Name of the custodian who held the asset over this stretch.",
            example = "Suresh Nair")
    private String assignedTo;

    @Schema(description = "When the asset arrived.", example = "2026-06-20T09:00:00")
    private LocalDateTime from;

    @Schema(description = "When it left. Null on the placement it is still in.",
            example = "2026-08-04T16:30:00")
    private LocalDateTime to;

    @Schema(description = "How many whole days the placement lasted, counted to now on the "
            + "placement the asset is still in.", example = "45")
    private long days;

    @Schema(description = "Whether this is the placement the asset is in now.", example = "false")
    private boolean current;

    @Schema(description = "Why the asset arrived here.",
            example = "Mobilised to the Marina Heights site for the piling phase")
    private String reason;
}
