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

    @Schema(nullable = true, description = "Id of the leave request this transaction is linked to. Null "
            + "for transactions with no originating request, such as a monthly accrual or a manual "
            + "adjustment.", example = "241")
    private Long leaveRequestId;

    @Schema(nullable = true, description = "Request number of the linked leave request. Null whenever "
            + "leaveRequestId is, since it is read from that request.", example = "LR-2026-0241")
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

    @Schema(nullable = true, description = "Month the transaction is attributed to. Written only by the "
            + "accrual service, so it is null on deductions and manual adjustments.", example = "9")
    private Integer referenceMonth;

    @Schema(nullable = true, description = "Year the transaction is attributed to. Written only by the "
            + "accrual service, so it is null on deductions and manual adjustments.", example = "2026")
    private Integer referenceYear;

    @Schema(nullable = true, description = "Description of the transaction. Written on every path the "
            + "application takes (accrual, approval deduction and manual adjustment), but the column permits "
            + "null, so a row loaded outside the application can carry none.",
            example = "Deduction for leave request LR-2026-0241")
    private String description;

    @Schema(nullable = true, description = "Id of the employee who created the transaction. Written only on "
            + "a manual adjustment, so it is null on the accrual and approval-deduction rows the system "
            + "posts itself.", example = "2")
    private Long createdById;

    @Schema(nullable = true, description = "Name of the employee who created the transaction. Always null "
            + "on this response: the mapper leaves the field unset and nothing else fills it in, so resolve "
            + "the name from createdById.")
    private String createdByName;

    @Schema(description = "Time the transaction was recorded.", example = "2026-09-14T09:30:00")
    private LocalDateTime createdAt;
}
