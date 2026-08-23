package org.tornotron.echno_backend.subcontract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "A payment milestone within a subcontract.")
@Data
public class ContractMilestoneDto {
    @Schema(description = "Milestone id.", example = "51")
    private Long id;

    @Schema(description = "Name of the milestone.", example = "Ground floor slab casting")
    private String name;

    @Schema(description = "Description of the work due at this milestone.", example = "Shuttering, reinforcement and concreting of the ground floor slab")
    private String description;

    @Schema(description = "Planned completion date.", example = "2026-03-15")
    private LocalDate targetDate;

    @Schema(description = "Actual completion date, once reached.", example = "2026-03-20")
    private LocalDate completionDate;

    @Schema(description = "Percentage of the contract value released at this milestone.", example = "20.00")
    private BigDecimal paymentPercentage;

    @Schema(description = "Payment amount for this milestone in INR.", example = "640000.00")
    private BigDecimal amount;

    @Schema(description = "Status of the milestone.", example = "PENDING")
    private String status;
}
