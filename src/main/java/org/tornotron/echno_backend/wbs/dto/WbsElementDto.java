package org.tornotron.echno_backend.wbs.dto;

import lombok.Data;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.wbs.enums.WbsStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class WbsElementDto {
    private Long id;
    private String wbsCode;
    private String title;
    private String description;
    private Integer level;
    private Integer sortOrder;
    private WbsStatus status;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDate actualStartDate;
    private LocalDate actualEndDate;
    private BigDecimal budgetedCost;
    private BigDecimal actualCost;
    private Double progress;
    private Double weight;
    private Boolean isLeaf;
    private Long projectId;
    private String projectName;
    private Long parentId;
    private String parentWbsCode;
    private EmployeeDto createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<WbsElementDto> children;
}
