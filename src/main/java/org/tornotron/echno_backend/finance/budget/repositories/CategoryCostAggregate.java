package org.tornotron.echno_backend.finance.budget.repositories;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Per cost-category roll-up of construction invoice line amounts for one project, produced by a
 * single grouped query so the cost-control view does not fan out into per-invoice reads.
 *
 * <p>{@code committed} sums lines on approved-but-not-fully-paid invoices; {@code spent} sums lines
 * on fully paid invoices. Both are restricted to lines tagged to the category.
 */
public interface CategoryCostAggregate {
    UUID getCostCategoryId();
    BigDecimal getCommitted();
    BigDecimal getSpent();
}
