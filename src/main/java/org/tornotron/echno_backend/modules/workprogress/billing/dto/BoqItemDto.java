package org.tornotron.echno_backend.modules.workprogress.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "One line of a contract's bill of quantities, with what has been certified against it so far.")
public record BoqItemDto(
        UUID id,
        Long subContractId,
        String itemCode,
        String description,
        String unit,
        BigDecimal contractQuantity,
        BigDecimal rate,
        @Schema(description = "Contract quantity times rate, in rupees.")
        BigDecimal amount,
        @Schema(nullable = true)
        Long wbsElementId,
        @Schema(description = "wbsCode of the linked schedule activity.", nullable = true)
        String wbsCode,
        int sortOrder,
        @Schema(description = "Quantity accepted on certified and approved bills so far.")
        BigDecimal certifiedQuantity,
        @Schema(description = "Whether a bill uses the item, which stops it being deleted.")
        boolean inUse
) {}
