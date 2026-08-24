package org.tornotron.echno_backend.inventoryTransaction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface InventoryTransactionRepository extends JpaRepository<InventoryTransaction, Long> {

    Optional<InventoryTransaction> findByIdAndOrganization_Id(Long id, Long organizationId);

    List<InventoryTransaction> findByMaterialId(Long materialId);

    /**
     * A material's movement history as a page, oldest movement first so the caller reads it
     * as a forward-running timeline. The id is a stable tie-break when two movements share a
     * transaction date. Tenant scoping is applied by the {@code orgFilter} Hibernate filter,
     * as with the other ledger reads.
     */
    @Query("SELECT it FROM InventoryTransaction it WHERE it.material.id = :materialId " +
           "ORDER BY it.transactionDate ASC, it.id ASC")
    Page<InventoryTransaction> findMovementHistoryByMaterial(@Param("materialId") Long materialId, Pageable pageable);

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

    List<InventoryTransaction> findByStorageLocationIdAndMaterialIdAndProjectId(Long storageLocationId, Long materialId,Long projectId);

    List<InventoryTransaction> findByTaskId(Long taskId);

    List<InventoryTransaction> findByProjectIdAndTaskIsNotNull(Long projectId);

    @Query("SELECT it.material.id, it.material.materialName, COALESCE(SUM(it.quantityChanged), 0.0) " +
           "FROM InventoryTransaction it WHERE it.storageLocation.id = :storageLocationId " +
           "GROUP BY it.material.id, it.material.materialName")
    List<Object[]> findStockGroupedByMaterialAtStorageLocation(@Param("storageLocationId") Long storageLocationId);
}
