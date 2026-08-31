package org.tornotron.echno_backend.purchaseOrderItem;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface PurchaseOrderItemRepository extends JpaRepository<PurchaseOrderItem, Long> {

    void deleteByIdAndOrganization_Id(Long id, Long organizationId);

    Optional<PurchaseOrderItem> findByIdAndOrganization_Id(Long id, Long organizationId);

    List<PurchaseOrderItem> findByPurchaseOrderId(Long purchaseOrderId);

    List<PurchaseOrderItem> findByMaterialId(Long materialId);

    @Query("SELECT SUM(poi.totalPrice) FROM PurchaseOrderItem poi WHERE poi.purchaseOrder.id = :purchaseOrderId")
    BigDecimal sumTotalPriceByPurchaseOrderId(@Param("purchaseOrderId") Long purchaseOrderId);

    /**
     * Loads an order's lines for a read-modify-write on {@code receivedQuantity} under a
     * pessimistic write lock, ordered by id so two callers take the rows in the same sequence
     * and cannot deadlock against each other.
     *
     * <p>Recording a receipt reads the quantity already received on a line, decides whether the
     * new quantity over-receives it, and writes the sum back. Two goods receipts against the
     * same order at the same time would each read the figure as it stood before the other, so
     * both would pass the over-receipt check and the second write would erase the first. The
     * order would then read as under-received while the stock ledger holds both deliveries,
     * which is the shape of balance nobody can explain. On CockroachDB {@code SELECT … FOR
     * UPDATE} queues the second caller rather than aborting it, so it reads the committed
     * figure and is judged against the truth.
     *
     * <p>Reads that only display an order keep {@link #findByPurchaseOrderId}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT poi FROM PurchaseOrderItem poi WHERE poi.purchaseOrder.id = :purchaseOrderId "
            + "AND poi.organization.id = :orgId ORDER BY poi.id")
    List<PurchaseOrderItem> lockByPurchaseOrderIdAndOrganizationId(
            @Param("purchaseOrderId") Long purchaseOrderId, @Param("orgId") Long orgId);
}
