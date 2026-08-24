package org.tornotron.echno_backend.finance.construction.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.finance.budget.repositories.CategoryCostAggregate;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceStatus;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentStatus;
import org.tornotron.echno_backend.finance.construction.domain.ConstructionInvoiceLine;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ConstructionInvoiceLineRepository extends JpaRepository<ConstructionInvoiceLine, UUID> {

    /**
     * Rolls up tagged invoice line totals by cost category for one project, in a single grouped query.
     *
     * <p>Only lines carrying a cost category count. For each head:
     * <ul>
     *   <li><b>spent</b> sums line totals on invoices whose payment status is fully paid.</li>
     *   <li><b>committed</b> sums line totals on invoices that are approved or beyond (a status in
     *       {@code committedStatuses}) and not yet fully paid.</li>
     * </ul>
     * The organization id is passed explicitly rather than relying on the Hibernate {@code orgFilter}
     * so the roll-up is correct regardless of whether the filter is enabled on the session.
     */
    @Query("""
            SELECT l.costCategory.id AS costCategoryId,
                   COALESCE(SUM(CASE WHEN l.invoice.paymentStatus = :paid THEN l.total ELSE 0 END), 0) AS spent,
                   COALESCE(SUM(CASE WHEN l.invoice.paymentStatus <> :paid
                                      AND l.invoice.status IN :committedStatuses
                                     THEN l.total ELSE 0 END), 0) AS committed
            FROM ConstructionInvoiceLine l
            WHERE l.invoice.projectId = :projectId
              AND l.invoice.organization.id = :organizationId
              AND l.costCategory IS NOT NULL
            GROUP BY l.costCategory.id
            """)
    List<CategoryCostAggregate> aggregateByCategoryForProject(
            @Param("projectId") Long projectId,
            @Param("organizationId") Long organizationId,
            @Param("paid") ConstructionPaymentStatus paid,
            @Param("committedStatuses") Collection<ConstructionInvoiceStatus> committedStatuses);
}
