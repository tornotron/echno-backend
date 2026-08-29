package org.tornotron.echno_backend.goodsReceivedNote;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface GoodsReceivedNoteRepository extends JpaRepository<GoodsReceivedNote, Long> {

    Optional<GoodsReceivedNote> findByIdAndOrganization_Id(Long id, Long organizationId);

    Optional<GoodsReceivedNote> findByGrnNumber(String grnNumber);

    List<GoodsReceivedNote> findByVendorId(Long vendorId);

    List<GoodsReceivedNote> findByReceivedOnBetween(LocalDateTime startDate, LocalDateTime endDate);
}
