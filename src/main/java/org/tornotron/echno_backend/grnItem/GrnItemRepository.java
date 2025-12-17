package org.tornotron.echno_backend.grnItem;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GrnItemRepository extends JpaRepository<GrnItem, Long> {

    List<GrnItem> findByGoodsReceivedNoteId(Long grnId);

    List<GrnItem> findByMaterialId(Long materialId);
}
