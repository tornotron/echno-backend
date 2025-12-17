package org.tornotron.echno_backend.siteTransferItem;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SiteTransferItemRepository extends JpaRepository<SiteTransferItem, Long> {

    List<SiteTransferItem> findBySiteTransferId(Long siteTransferId);

    List<SiteTransferItem> findByMaterialId(Long materialId);
}
