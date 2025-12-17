package org.tornotron.echno_backend.inventoryTransaction;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;

import java.time.LocalDateTime;
import java.util.List;

public interface InventoryTransactionRepository extends JpaRepository<InventoryTransaction, Long> {

    List<InventoryTransaction> findByMaterialId(Long materialId);

    List<InventoryTransaction> findByTransactionType(InventoryTransactionType transactionType);

    List<InventoryTransaction> findByTransactionDateBetween(LocalDateTime startDate, LocalDateTime endDate);

    @Query("SELECT it FROM InventoryTransaction it WHERE it.material.id = :materialId " +
           "ORDER BY it.transactionDate DESC, it.id DESC")
    List<InventoryTransaction> findTopByMaterialIdOrderByTransactionDateDescIdDesc(
            @Param("materialId") Long materialId, Pageable pageable);

    default List<InventoryTransaction> findLatestTransactionForMaterial(Long materialId) {
        return findTopByMaterialIdOrderByTransactionDateDescIdDesc(materialId,
                org.springframework.data.domain.PageRequest.of(0, 1));
    }
}
