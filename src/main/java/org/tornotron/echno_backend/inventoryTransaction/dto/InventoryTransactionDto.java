package org.tornotron.echno_backend.inventoryTransaction.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "A single inventory transaction: one stock movement of a material, carrying the "
        + "opening, changed and closing quantity along with the project, task and storage location it "
        + "applies to.")
public class InventoryTransactionDto {

    @Schema(description = "Unique transaction id.", example = "5012")
    private Long id;

    @Schema(description = "When the movement was recorded.", example = "2026-08-01T10:30:00")
    private LocalDateTime transactionDate;

    @Schema(description = "Material that moved.", example = "310")
    private Long materialId;

    @Schema(description = "Material name at the time of the movement.", example = "Portland Cement 53 grade")
    private String materialName;

    @Schema(description = "Stock on hand before this movement.", example = "120.0")
    private Double openingStock;

    @Schema(description = "Signed change applied by this movement. Positive for stock in, negative for stock out.",
            example = "-15.0")
    private Double quantityChanged;

    @Schema(description = "Stock on hand after this movement.", example = "105.0")
    private Double closingStock;

    @Schema(description = "Movement type, for example GRN, USE, TRANSFER_OUT, TRANSFER_IN, ADJUST or OPENING_BALANCE.",
            example = "USE")
    private InventoryTransactionType transactionType;

    @Schema(description = "Source document reference for the movement, for example a GRN or challan number.",
            example = "GRN-2026-0042")
    private String referenceNumber;

    @Schema(description = "Free-text remarks about the movement.", example = "Issued to slab casting crew")
    private String remarks;

    @Schema(description = "Project the movement is booked against.", example = "42")
    private Long projectId;

    @Schema(description = "Project name.", example = "Tower B fit-out")
    private String projectName;

    @Schema(description = "Storage location the movement applies to.", example = "7")
    private Long storageLocationId;

    @Schema(description = "Storage location name.", example = "Site A main store")
    private String storageLocationName;

    @Schema(description = "Task the movement is booked against, when the movement is task consumption.",
            example = "884")
    private Long taskId;

    @Schema(description = "Task title.", example = "Second floor slab casting")
    private String taskTitle;

    @Schema(description = "Employee who recorded the movement.")
    private EmployeeDto createdBy;

    @Schema(description = "Unit cost applied to the moved quantity.", example = "395.00")
    private BigDecimal unitCost;
}
