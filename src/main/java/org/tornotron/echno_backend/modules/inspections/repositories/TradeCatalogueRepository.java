package org.tornotron.echno_backend.modules.inspections.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.inspections.domain.TradeCatalogueEntry;

import java.util.List;

@Repository
public interface TradeCatalogueRepository extends JpaRepository<TradeCatalogueEntry, String> {

    List<TradeCatalogueEntry> findAllByOrderBySortOrderAscCodeAsc();

    List<TradeCatalogueEntry> findByActiveTrueOrderBySortOrderAscCodeAsc();
}
