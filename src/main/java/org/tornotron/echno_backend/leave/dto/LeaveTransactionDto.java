package org.tornotron.echno_backend.leave.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.leave.enums.TransactionType;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "One entry in an employee's leave balance ledger, such as an accrual, deduction "
        + "or manual adjustment.")
@Data
public class LeaveTransactionDto {
    @Schema(description = "Id of the transaction.", example = "5031")
    private Long id;

    @Schema(description = "Id of the employee this transaction belongs to.", example = "18")
    private Long employeeId;

    @Schema(description = "Name of the employee this transaction belongs to.", example = "Ravi Kumar")
    private String employeeName;

    @Schema(description = "Id of the leave balance this transaction was posted against.", example = "88")
    private Long leaveBalanceId;

    @Schema(description = "Name of the leave type the balance is tracked under.", example = "Casual Leave")
    private String leaveTypeName;

    @Schema(description = "Id of the leave request this transaction is linked to, if any.", example = "241")
    private Long leaveRequestId;

    @Schema(description = "Request number of the linked leave request, if any.", example = "LR-2026-0241")
    private String requestNumber;

    @Schema(description = "Type of the transaction.", example = "DEDUCTION")
    private TransactionType transactionType;

    @Schema(description = "Signed number of days moved by this transaction.", example = "-2.5")
    private Double days;

    @Schema(description = "Balance immediately before this transaction.", example = "9.0")
    private Double balanceBefore;

    @Schema(description = "Balance immediately after this transaction.", example = "6.5")
    private Double balanceAfter;

    @Schema(description = "Date the transaction is effective.", example = "2026-09-14")
    private LocalDate transactionDate;

    @Schema(description = "Month the transaction is attributed to, for accrual transactions.", example = "9")
    private Integer referenceMonth;

    @Schema(description = "Year the transaction is attributed to.", example = "2026")
    private Integer referenceYear;

    @Schema(description = "Description of the transaction.", example = "Deduction for leave request LR-2026-0241")
    private String description;

    @Schema(description = "Id of the employee who created the transaction, for manual adjustments.", example = "2")
    private Long createdById;

    @Schema(description = "Name of the employee who created the transaction, for manual adjustments.", example = "Anand Rajan")
    private String createdByName;

    @Schema(description = "Time the transaction was recorded.", example = "2026-09-14T09:30:00")
    private LocalDateTime createdAt;
}
