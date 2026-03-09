package org.tornotron.echno_backend.payable.dto;

import lombok.Data;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.payable.enums.ContractType;
import org.tornotron.echno_backend.user.dto.UserDto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PayableDto {

    private Long id;
    private String payableNumber;
    private String contractorName;
    private ContractType contractType;
    private BigDecimal amountRecorded;
    private BigDecimal amountPaid;
    private BigDecimal amountDue;
    private Long vendorId;
    private String vendorName;
    private Long goodsReceivedNoteId;
    private String grnNumber;
    private EmployeeDto createdBy;
    private LocalDateTime createdAt;
}
