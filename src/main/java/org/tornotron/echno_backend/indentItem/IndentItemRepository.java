package org.tornotron.echno_backend.indentItem;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IndentItemRepository extends JpaRepository<IndentItem, Long> {

    List<IndentItem> findByIntendId(Long intendId);

    List<IndentItem> findByMaterialId(Long materialId);

    List<IndentItem> findByConvertedToPurchaseOrder(Boolean converted);
}
