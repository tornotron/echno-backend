package org.tornotron.echno_backend.purchaseOrder;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tornotron.echno_backend.purchaseOrder.enums.PurchaseOrderStatus;

import java.util.List;
import java.util.Optional;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    Optional<PurchaseOrder> findByPoNumber(String poNumber);

    List<PurchaseOrder> findByVendorId(Long vendorId);

    List<PurchaseOrder> findByIndentId(Long indentId);

    List<PurchaseOrder> findByStatus(PurchaseOrderStatus status);

    boolean existsByPoNumber(String poNumber);
}
