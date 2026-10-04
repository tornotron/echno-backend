package org.tornotron.echno_backend.modules.workprogress.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.tornotron.echno_backend.modules.workprogress.billing.domain.BillingModel;

@Schema(description = "Opens a bill on a contract. The first bill fixes the contract's billing model. A running account bill needs its period and lists every BOQ item; a milestone bill needs its milestone.")
public record CreateBillRequest(
        @NotNull Long subContractId,
        @Schema(description = "RUNNING_ACCOUNT or MILESTONE.", example = "RUNNING_ACCOUNT")
        @NotNull BillingModel billingModel,
        @Schema(description = "First day of the billing period, for a running account bill.", nullable = true)
        LocalDate periodFrom,
        @Schema(description = "Last day of the billing period, for a running account bill.", nullable = true)
        LocalDate periodTo,
        @Schema(description = "The contract milestone billed, for a milestone bill.", nullable = true)
        Long contractMilestoneId,
        @Schema(description = "Percent of the milestone value claimed, for a milestone bill.", nullable = true)
        @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal claimedPercent,
        @Schema(description = "The contractor's own bill or invoice number.", nullable = true)
        @Size(max = 100) String contractorReference,
        @Schema(description = "Where the work billed was done.", example = "Tower B, level 2", nullable = true)
        @Size(max = 200) String location,
        @Schema(nullable = true)
        @Size(max = 4000) String remarks
) {}
