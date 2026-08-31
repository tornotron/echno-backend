package org.tornotron.echno_backend.inventoryTransaction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.inventoryTransaction.enums.InventoryTransactionType;

import java.math.BigDecimal;
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
     *
     * <p>The storage location, project and creator are fetch-joined because the timeline DTO
     * reads a field off each one. All three are {@code @ManyToOne}, so the fetch join stays a
     * to-one join: it neither multiplies rows nor pushes Hibernate into in-memory pagination,
     * and it keeps the page to one statement instead of one per row per association. They are
     * LEFT joins because {@code storageLocation} and {@code createdBy} are nullable. Deliberately
     * no collection is fetched here, which is what would break paging.
     *
     * <p>The count query is spelled out because a derived count cannot be built from a query
     * carrying fetch joins.
     */
    @Query(value = "SELECT it FROM InventoryTransaction it " +
                   "LEFT JOIN FETCH it.storageLocation " +
                   "LEFT JOIN FETCH it.project " +
                   "LEFT JOIN FETCH it.createdBy " +
                   "WHERE it.material.id = :materialId " +
                   "ORDER BY it.transactionDate ASC, it.id ASC",
           countQuery = "SELECT COUNT(it) FROM InventoryTransaction it WHERE it.material.id = :materialId")
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

    /**
     * The unit cost the earliest movement of one type carried for a material against a document.
     *
     * <p>This exists so a movement that answers an earlier one can be priced at what the earlier
     * one carried. A site transfer's inbound leg is written days after its outbound leg, by which
     * time the sending balance has moved on and may hold nothing at all: pricing the arrival off
     * that balance would value it at zero and silently destroy the stock value that left the site.
     * The outbound movement is the only record of what the material was worth when it went, so it
     * is read back rather than recomputed.
     *
     * <p>Ordered by id, with the caller passing a {@code Pageable} of one row, so a document that
     * wrote more than one movement of the same type for the same material yields the first
     * deterministically instead of raising a non-unique result. Deliberately not wrapped in a
     * default method: a default method on a repository interface is mocked along with the rest of
     * it, so a caller's stub of this query would be silently bypassed in tests.
     */
    @Query("SELECT it.unitCost FROM InventoryTransaction it "
            + "WHERE it.referenceNumber = :referenceNumber AND it.material.id = :materialId "
            + "AND it.transactionType = :transactionType AND it.organization.id = :organizationId "
            + "AND it.unitCost IS NOT NULL ORDER BY it.id")
    List<BigDecimal> findUnitCostsForReference(@Param("referenceNumber") String referenceNumber,
                                               @Param("materialId") Long materialId,
                                               @Param("transactionType") InventoryTransactionType transactionType,
                                               @Param("organizationId") Long organizationId,
                                               Pageable pageable);

}
