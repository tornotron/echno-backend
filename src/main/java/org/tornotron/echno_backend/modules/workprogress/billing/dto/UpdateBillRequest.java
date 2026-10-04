package org.tornotron.echno_backend.modules.workprogress.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "Replaces the claim of a draft or returned bill: its header and, for a running account bill, the claimed quantities of the lines named.")
public record UpdateBillRequest(
        @Schema(nullable = true)
        LocalDate periodFrom,
        @Schema(nullable = true)
        LocalDate periodTo,
        @Schema(description = "Percent of the milestone value claimed, for a milestone bill.", nullable = true)
        @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal claimedPercent,
        @Schema(nullable = true)
        @Size(max = 100) String contractorReference,
        @Schema(nullable = true)
        @Size(max = 200) String location,
        @Schema(nullable = true)
        @Size(max = 4000) String remarks,
        @Schema(description = "Lines to change; a line not named keeps its claim.", nullable = true)
        @Valid @Size(max = 2000) List<ClaimLineRequest> lines
) {}
