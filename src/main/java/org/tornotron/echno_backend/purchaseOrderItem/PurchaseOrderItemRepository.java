package org.tornotron.echno_backend.purchaseOrderItem;

import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface PurchaseOrderItemRepository extends JpaRepository<PurchaseOrderItem, Long> {

    List<PurchaseOrderItem> findByPurchaseOrderId(Long purchaseOrderId);

    List<PurchaseOrderItem> findByMaterialId(Long materialId);

    @Query("SELECT SUM(poi.totalPrice) FROM PurchaseOrderItem poi WHERE poi.purchaseOrder.id = :purchaseOrderId")
    BigDecimal sumTotalPriceByPurchaseOrderId(@Param("purchaseOrderId") Long purchaseOrderId);
}
