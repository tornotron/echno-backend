package org.tornotron.echno_backend.purchaseOrder.dto;

import lombok.Data;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.purchaseOrder.enums.PurchaseOrderStatus;
import org.tornotron.echno_backend.user.dto.UserDto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class PurchaseOrderDto {

    private Long id;
    private String poNumber;
    private Long vendorId;
    private String vendorName;
    private Long intendId;
    private String intendNumber;
    private PurchaseOrderStatus status;
    private LocalDateTime createdAt;
    private EmployeeDto createdBy;
    private LocalDateTime expectedDeliveryDate;
    private String remarks;
    private List<PurchaseOrderItemDto> items;
    private BigDecimal totalAmount;
}
