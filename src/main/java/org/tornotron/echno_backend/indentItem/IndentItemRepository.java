package org.tornotron.echno_backend.indentItem;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IndentItemRepository extends JpaRepository<IndentItem, Long> {

    Optional<IndentItem> findByIdAndOrganization_Id(Long id, Long organizationId);

    List<IndentItem> findByIndentId(Long indentId);

    List<IndentItem> findByMaterialId(Long materialId);

    List<IndentItem> findByConvertedToPurchaseOrder(Boolean converted);

    boolean existsByIdAndOrganization_Id(Long id, Long organizationId);

    void deleteByIdAndOrganization_Id(Long id, Long organizationId);
}
