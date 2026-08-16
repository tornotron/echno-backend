package org.tornotron.echno_backend.subcontract.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class ContractMilestoneDto {
    private Long id;
    private String name;
    private String description;
    private LocalDate targetDate;
    private LocalDate completionDate;
    private BigDecimal paymentPercentage;
    private BigDecimal amount;
    private String status;
}
