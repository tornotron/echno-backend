package org.tornotron.echno_backend.intend.dto;

import lombok.Data;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.indentItem.dto.IndentItemDto;
import org.tornotron.echno_backend.intend.enums.IntendStatus;
import org.tornotron.echno_backend.user.dto.UserDto;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class IntendDto {
    private Long id;
    private String intendNumber;
    private LocalDateTime createdAt;
    private EmployeeDto createdBy;
    private IntendStatus status;
    private LocalDateTime expectedOn;
    private String remarks;
    private List<IndentItemDto> items;
}
