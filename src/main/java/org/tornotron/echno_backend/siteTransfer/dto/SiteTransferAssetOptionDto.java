package org.tornotron.echno_backend.siteTransfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "An asset a site transfer can send: one that is at the sending project and "
        + "storage location, and not already in transit on another transfer.")
@Data
public class SiteTransferAssetOptionDto {

    @Schema(description = "Database id of the asset, sent as assetId on an ASSET line.", example = "12")
    private Long id;

    @Schema(description = "The organization's own code for the asset.", example = "AST-0021", nullable = true)
    private String assetCode;

    @Schema(description = "Name of the asset.", example = "JCB 3DX Backhoe Loader")
    private String name;

    @Schema(description = "Asset type, a kebab-case value defined by the frontend.",
            example = "heavy-equipment", nullable = true)
    private String type;

    @Schema(description = "Current lifecycle status, a kebab-case value defined by the frontend.",
            example = "in-use", nullable = true)
    private String status;

    @Schema(description = "Name of the person the asset is assigned to.", example = "Ravi Kumar", nullable = true)
    private String assignedTo;
}
