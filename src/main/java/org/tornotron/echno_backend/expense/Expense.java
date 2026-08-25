package org.tornotron.echno_backend.expense;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A recorded expense. Type, category and status are stored as plain strings because
 * the web client sends its own lowercase vocabulary (for example {@code direct},
 * {@code materials}, {@code reimbursed}) that is not a valid Java enum identifier; the
 * frontend validates them.
 *
 * <p>The optional links ({@code projectId}, {@code vendorId}, {@code employeeId},
 * {@code invoiceId}, {@code paymentId}, {@code budgetId}) are kept as plain nullable
 * columns rather than JPA relationships, keeping the module self-contained and letting
 * an expense point at rows that may not yet exist.
 */
@Entity
@Table(
        name = "expenses",
        uniqueConstraints = @UniqueConstraint(name = "uk_expense_number",
                columnNames = {"organization_id", "expense_number"})
)
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
@Getter
@Setter
@NoArgsConstructor
public class Expense implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "expense_number", nullable = false, length = 30)
    private String expenseNumber;

    @Column(name = "type", length = 50)
    private String type;

    @Column(name = "category", length = 50)
    private String category;

    @Column(name = "status", length = 50)
    private String status;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "amount", precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", length = 10)
    private String currency = "INR";

    @Column(name = "expense_date")
    private LocalDate expenseDate;

    @Column(name = "payment_method")
    private String paymentMethod;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "vendor_id")
    private Long vendorId;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "invoice_id")
    private Long invoiceId;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "budget_id")
    private Long budgetId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
