package org.tornotron.echno_backend.modules.inspections.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.inspections.domain.ElementTypeCatalogueEntry;

import java.util.List;

@Repository
public interface ElementTypeCatalogueRepository extends JpaRepository<ElementTypeCatalogueEntry, String> {

    List<ElementTypeCatalogueEntry> findAllByOrderBySortOrderAscCodeAsc();

    List<ElementTypeCatalogueEntry> findByActiveTrueOrderBySortOrderAscCodeAsc();
}
