package org.tornotron.echno_backend.goodsReceivedNote;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface GoodsReceivedNoteRepository extends JpaRepository<GoodsReceivedNote, Long>,
        JpaSpecificationExecutor<GoodsReceivedNote> {

    Optional<GoodsReceivedNote> findByIdAndOrganization_Id(Long id, Long organizationId);

    Optional<GoodsReceivedNote> findByGrnNumber(String grnNumber);

    List<GoodsReceivedNote> findByVendorId(Long vendorId);

    Page<GoodsReceivedNote> findByVendorId(Long vendorId, Pageable pageable);

    /** The receipts recorded against one order. Bounded by the deliveries an order takes. */
    List<GoodsReceivedNote> findByPurchaseOrder_IdAndOrganization_Id(Long purchaseOrderId, Long organizationId);

    List<GoodsReceivedNote> findByReceivedOnBetween(LocalDateTime startDate, LocalDateTime endDate);

    Page<GoodsReceivedNote> findByReceivedOnBetween(LocalDateTime startDate, LocalDateTime endDate, Pageable pageable);
}
