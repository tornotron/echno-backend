package org.tornotron.echno_backend.leave.dto;

import lombok.Data;

@Data
public class LeavePolicySimpleDto {
    private Long id;
    private String leaveTypeCode;
    private String leaveTypeName;
    private Double annualQuota;
    private Boolean allowHalfDay;
    private Boolean isPaid;
}
