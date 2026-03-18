package org.tornotron.echno_backend.wbs.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class WbsElementCreationDto {

    @NotBlank(message = "wbsCode is required")
    @Size(max = 50, message = "wbsCode must not exceed 50 characters")
    private String wbsCode;

    @NotBlank(message = "title is required")
    @Size(max = 255, message = "title must not exceed 255 characters")
    private String title;

    private String description;

    private Long parentId;

    private Integer sortOrder;

    private String status;

    private LocalDate startDate;

    private LocalDate endDate;

    private BigDecimal budgetedCost;

    private Double weight;

    private Long createdBy;
}
