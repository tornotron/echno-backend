package org.tornotron.echno_backend.expense.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "An expense with its category, amount and optional links to project, vendor and finance rows.")
@Data
public class ExpenseDto {

    @Schema(description = "Expense id.", example = "42")
    private Long id;

    @Schema(description = "Generated expense number.", example = "EXP-2027-000001")
    private String expenseNumber;

    @Schema(description = "Nature of the expense against the project cost.", example = "direct")
    private String type;

    @Schema(description = "Spend category.", example = "materials")
    private String category;

    @Schema(description = "Lifecycle status of the expense.", example = "pending")
    private String status;

    @Schema(description = "What the expense was for.", example = "Cement and steel for Block C slab")
    private String description;

    @Schema(description = "Expense amount in the given currency.", example = "45000.00")
    private BigDecimal amount;

    @Schema(description = "Currency code for the amount.", example = "INR")
    private String currency;

    @Schema(description = "Date the expense was incurred.", example = "2026-08-20")
    private LocalDate expenseDate;

    @Schema(description = "How the expense was paid.", example = "Bank Transfer")
    private String paymentMethod;

    @Schema(description = "Free-text notes.", example = "Reimbursed to site engineer against bill 2291")
    private String notes;

    @Schema(description = "Id of the project this expense belongs to.", example = "3")
    private Long projectId;

    @Schema(description = "Id of the vendor the expense was paid to.", example = "12")
    private Long vendorId;

    @Schema(description = "Id of the employee who incurred the expense.", example = "9")
    private Long employeeId;

    @Schema(description = "Id of the invoice this expense settles.", example = "44")
    private Long invoiceId;

    @Schema(description = "Id of the payment that cleared the expense.", example = "51")
    private Long paymentId;

    @Schema(description = "Id of the budget head the expense draws down.", example = "7")
    private Long budgetId;

    @Schema(description = "Id of the owning organization.", example = "1")
    private Long organizationId;

    @Schema(description = "Timestamp the expense was created.", example = "2026-08-20T09:00:00")
    private LocalDateTime createdAt;

    @Schema(description = "Timestamp the expense was last updated.", example = "2026-08-22T14:20:00")
    private LocalDateTime updatedAt;
}
