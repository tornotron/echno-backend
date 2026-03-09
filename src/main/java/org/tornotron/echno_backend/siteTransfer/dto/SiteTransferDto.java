package org.tornotron.echno_backend.siteTransfer.dto;

import lombok.Data;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.siteTransfer.enums.SiteTransferStatus;
import org.tornotron.echno_backend.user.dto.UserDto;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class SiteTransferDto {

    private Long id;
    private String transferNumber;
    private LocalDateTime issueDate;
    private EmployeeDto sendingPerson;
    private String receivingSite;
    private SiteTransferStatus status;
    private List<SiteTransferItemDto> items;
}
