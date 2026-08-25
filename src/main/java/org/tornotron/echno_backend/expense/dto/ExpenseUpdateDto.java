package org.tornotron.echno_backend.expense.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "Payload to update an expense. The expense number is immutable and not accepted here.")
@Data
public class ExpenseUpdateDto {

    @Schema(description = "Nature of the expense against the project cost.", example = "direct")
    private String type;

    @Schema(description = "Spend category.", example = "materials")
    private String category;

    @Schema(description = "Lifecycle status of the expense.", example = "approved")
    private String status;

    @Schema(description = "What the expense was for.", example = "Cement and steel for Block C slab")
    @NotBlank
    private String description;

    @Schema(description = "Expense amount in the given currency.", example = "45000.00")
    @NotNull
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
}
