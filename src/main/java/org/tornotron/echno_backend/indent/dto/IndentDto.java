package org.tornotron.echno_backend.indent.dto;

import lombok.Data;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.indentItem.dto.IndentItemDto;
import org.tornotron.echno_backend.indent.enums.IndentStatus;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class IndentDto {
    private Long id;
    private String indentNumber;
    private LocalDateTime createdAt;
    private EmployeeDto createdBy;
    private Long projectId;
    private String projectName;
    private IndentStatus status;
    private LocalDateTime expectedOn;
    private String remarks;
    private List<IndentItemDto> items;
}
