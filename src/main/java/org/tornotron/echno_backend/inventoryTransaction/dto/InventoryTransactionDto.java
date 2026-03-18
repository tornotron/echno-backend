package org.tornotron.echno_backend.inventoryTransaction.dto;

import lombok.Data;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class InventoryTransactionDto {

    private Long id;
    private LocalDateTime transactionDate;
    private Long materialId;
    private String materialName;
    private Double openingStock;
    private Double quantityChanged;
    private Double closingStock;
    private InventoryTransactionType transactionType;
    private String referenceNumber;
    private String remarks;
    private Long projectId;
    private String projectName;
    private Long storageLocationId;
    private String storageLocationName;
    private EmployeeDto createdBy;
    private BigDecimal unitCost;
}
