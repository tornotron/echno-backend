package org.tornotron.echno_backend.goodsReceivedNote.dto;

import lombok.Data;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.user.dto.UserDto;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class GoodsReceivedNoteDto {

    private Long id;
    private String grnNumber;
    private LocalDateTime receivedOn;
    private EmployeeDto receivedBy;
    private Long vendorId;
    private String vendorName;
    private Long purchaseOrderId;
    private String purchaseOrderNumber;
    private String deliveryChallanNumber;
    private String invoiceNumber;
    private Double invoiceAmount;
    private List<GrnItemDto> items;
}
