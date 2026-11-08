package org.tornotron.echno_backend.purchaseOrder;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tornotron.echno_backend.purchaseOrder.enums.PurchaseOrderStatus;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    Optional<PurchaseOrder> findByPoNumber(String poNumber);

    List<PurchaseOrder> findByVendorId(Long vendorId);

    Page<PurchaseOrder> findByVendorId(Long vendorId, Pageable pageable);

    List<PurchaseOrder> findByIndentId(Long indentId);

    Page<PurchaseOrder> findByIndentId(Long indentId, Pageable pageable);

    List<PurchaseOrder> findByStatus(PurchaseOrderStatus status);

    Page<PurchaseOrder> findByStatus(PurchaseOrderStatus status, Pageable pageable);

    Optional<PurchaseOrder> findByIdAndOrganization_Id(Long id, Long organizationId);
}
