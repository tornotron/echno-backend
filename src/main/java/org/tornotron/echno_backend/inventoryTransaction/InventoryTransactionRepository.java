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

    List<InventoryTransaction> findByProjectId(Long projectId);

    @Query("SELECT COALESCE(SUM(it.quantityChanged), 0.0) FROM InventoryTransaction it " +
           "WHERE it.material.id = :materialId AND it.project.id = :projectId")
    Double sumQuantityChangedByMaterialAndProject(
            @Param("materialId") Long materialId,
            @Param("projectId") Long projectId);

    @Query("SELECT COALESCE(SUM(it.quantityChanged), 0.0) FROM InventoryTransaction it " +
           "WHERE it.material.id = :materialId")
    Double sumQuantityChangedByMaterial(@Param("materialId") Long materialId);

    List<InventoryTransaction> findByStorageLocationId(Long storageLocationId);

    @Query("SELECT it.material.id, it.material.materialName, COALESCE(SUM(it.quantityChanged), 0.0) " +
           "FROM InventoryTransaction it WHERE it.storageLocation.id = :storageLocationId " +
           "GROUP BY it.material.id, it.material.materialName")
    List<Object[]> findStockGroupedByMaterialAtStorageLocation(@Param("storageLocationId") Long storageLocationId);
}
