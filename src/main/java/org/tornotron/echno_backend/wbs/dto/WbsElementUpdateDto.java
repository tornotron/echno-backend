package org.tornotron.echno_backend.wbs.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class WbsElementUpdateDto {

    @Size(max = 255, message = "title must not exceed 255 characters")
    private String title;

    private String description;

    private String status;

    private LocalDate startDate;

    private LocalDate endDate;

    private LocalDate actualStartDate;

    private LocalDate actualEndDate;

    private BigDecimal budgetedCost;

    private Double weight;

    private Integer sortOrder;

    private Double progress;
}
