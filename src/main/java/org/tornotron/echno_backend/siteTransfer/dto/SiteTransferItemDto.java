package org.tornotron.echno_backend.siteTransfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.tornotron.echno_backend.siteTransfer.enums.SiteTransferLineType;

@Schema(description = "A single line on a site transfer. A MATERIAL line names a material and the "
        + "quantity sent. An ASSET line names one asset from the asset register and always sends "
        + "one unit; the asset must be at the transfer's sending project and storage location, and "
        + "not in transit on another transfer.")
@Data
public class SiteTransferItemDto {

    @Schema(description = "Id of the transfer item.", example = "84")
    private Long id;

    @Schema(description = "What the line carries. Optional on create, and MATERIAL when left out, "
            + "so a payload that predates asset lines means what it always meant.",
            example = "MATERIAL")
    private SiteTransferLineType lineType;

    @Schema(description = "Id of the material being transferred. Required on a MATERIAL line and "
            + "must be left out on an ASSET line.", example = "21", nullable = true)
    private Long materialId;

    @Schema(description = "Name of the material being transferred. Null on an ASSET line.",
            example = "TMT Bar 12mm", nullable = true, accessMode = Schema.AccessMode.READ_ONLY)
    private String materialName;

    @Schema(description = "Id of the asset being transferred. Required on an ASSET line and must be "
            + "left out on a MATERIAL line.", example = "12", nullable = true)
    private Long assetId;

    @Schema(description = "The organization's own code for the asset. Null on a MATERIAL line.",
            example = "AST-0021", nullable = true, accessMode = Schema.AccessMode.READ_ONLY)
    private String assetCode;

    @Schema(description = "Name of the asset being transferred. Null on a MATERIAL line.",
            example = "JCB 3DX Backhoe Loader", nullable = true, accessMode = Schema.AccessMode.READ_ONLY)
    private String assetName;

    @Schema(description = "Quantity sent, in the material's unit. Always 1 on an ASSET line, since "
            + "an asset is one machine; any other value is refused.", example = "500")
    @NotNull(message = "sent quantity is required")
    @Min(value = 1, message = "sent quantity must be at least 1")
    private Integer sentQuantity;

    @Schema(description = "Quantity recorded as having arrived at the receiving site, in the "
            + "material's unit. Null while the transfer is in transit and nobody has confirmed "
            + "anything about this line yet, which is not the same as saying nothing arrived. A "
            + "transfer between two stores on one project is received in full at creation, since "
            + "the material never leaves that site's custody.",
            example = "18", nullable = true, accessMode = Schema.AccessMode.READ_ONLY)
    private Integer receivedQuantity;

    @Schema(description = "How much of this line is neither at the sending site nor recorded as "
            + "having reached the receiving one: sent minus received, or the whole sent quantity "
            + "while nothing has been received. On a transfer still in transit this is stock on a "
            + "lorry. On one that has been received it is an open variance, closed by a stock "
            + "adjustment naming this transfer rather than by the transfer writing a loss of its "
            + "own. Zero once everything sent has arrived.",
            example = "0", accessMode = Schema.AccessMode.READ_ONLY)
    private Integer inTransitQuantity;

    @Schema(description = "Optional remarks for this line item.", example = "For column casting, Block C")
    private String remarks;
}
