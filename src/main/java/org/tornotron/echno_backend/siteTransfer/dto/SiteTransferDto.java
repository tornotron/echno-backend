package org.tornotron.echno_backend.siteTransfer.dto;

import lombok.Data;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.siteTransfer.enums.SiteTransferStatus;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class SiteTransferDto {

    private Long id;
    private String transferNumber;
    private LocalDateTime issueDate;
    private EmployeeDto sendingPerson;
    private Long sendingProjectId;
    private String sendingProjectName;
    private Long receivingProjectId;
    private String receivingProjectName;
    private SiteTransferStatus status;
    private List<SiteTransferItemDto> items;
}
