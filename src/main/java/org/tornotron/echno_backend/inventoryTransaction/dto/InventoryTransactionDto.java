package org.tornotron.echno_backend.inventoryTransaction.dto;

import lombok.Data;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;
import org.tornotron.echno_backend.user.dto.UserDto;

import java.time.LocalDateTime;

@Data
public class InventoryTransactionDto {

    private Long id;
    private LocalDateTime transactionDate;
    private Long materialId;
    private String materialName;
    private Integer openingStock;
    private Integer quantityChanged;
    private Integer closingStock;
    private InventoryTransactionType transactionType;
    private String referenceNumber;
    private String remarks;
    private Long projectId;
    private String projectName;
    private Long storageLocationId;
    private String storageLocationName;
    private EmployeeDto createdBy;
}
