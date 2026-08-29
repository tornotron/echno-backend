package org.tornotron.echno_backend.inventoryTransaction;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CurrentStockRepository extends JpaRepository<CurrentStock, Long> {

    Optional<CurrentStock> findByMaterialIdAndProjectIdAndStorageLocationId(
            Long materialId, Long projectId, Long storageLocationId);

    /**
     * Inserts a zero-quantity stock row if one does not already exist for this
     * material/project/location, doing nothing on conflict. Called before taking the
     * pessimistic lock so the lock always has a row to hold: two events racing to
     * create the first record for the same key cannot both insert (which for a null
     * location the composite unique constraint would not catch, since NULLs are
     * distinct). Native because {@code ON CONFLICT DO NOTHING} has no JPQL form.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO current_stock
                (material_id, project_id, storage_location_id, organization_id,
                 current_quantity, stock_value, created_at, updated_at)
            VALUES (:materialId, :projectId, :storageLocationId, :organizationId,
                    0.0, 0, current_timestamp, current_timestamp)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    void seedZeroStockRow(@Param("materialId") Long materialId,
                          @Param("projectId") Long projectId,
                          @Param("storageLocationId") Long storageLocationId,
                          @Param("organizationId") Long organizationId);

    @Query("SELECT cs FROM CurrentStock cs WHERE cs.material.id = :materialId AND cs.project.id = :projectId AND cs.storageLocation IS NULL")
    Optional<CurrentStock> findByMaterialIdAndProjectIdAndStorageLocationIsNull(
            @Param("materialId") Long materialId, @Param("projectId") Long projectId);

    /**
     * Loads the stock row for a material at a storage location with a pessimistic
     * write lock (SELECT ... FOR UPDATE). Used by the stock mutation path so
     * concurrent updates to the same row serialize instead of overwriting each
     * other (which lost updates and could drive stock negative).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT cs FROM CurrentStock cs WHERE cs.material.id = :materialId "
            + "AND cs.project.id = :projectId AND cs.storageLocation.id = :storageLocationId")
    Optional<CurrentStock> lockByMaterialProjectAndLocation(
            @Param("materialId") Long materialId, @Param("projectId") Long projectId,
            @Param("storageLocationId") Long storageLocationId);

    /** Pessimistic-write variant of the no-storage-location lookup. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT cs FROM CurrentStock cs WHERE cs.material.id = :materialId "
            + "AND cs.project.id = :projectId AND cs.storageLocation IS NULL")
    Optional<CurrentStock> lockByMaterialProjectAndNoLocation(
            @Param("materialId") Long materialId, @Param("projectId") Long projectId);

    List<CurrentStock> findByStorageLocationIdAndOrganization_Id(Long storageLocationId, Long organizationId);

    @Query("SELECT COALESCE(SUM(cs.currentQuantity), 0.0) FROM CurrentStock cs WHERE cs.material.id = :materialId AND cs.project.id = :projectId")
    Double sumCurrentQuantityByMaterialAndProject(
            @Param("materialId") Long materialId, @Param("projectId") Long projectId);

    @Query("SELECT COALESCE(SUM(cs.currentQuantity), 0.0) FROM CurrentStock cs WHERE cs.material.id = :materialId")
    Double sumCurrentQuantityByMaterial(@Param("materialId") Long materialId);

    @Query("SELECT COALESCE(SUM(cs.stockValue), 0) FROM CurrentStock cs WHERE cs.material.id = :materialId AND cs.project.id = :projectId")
    BigDecimal sumStockValueByMaterialAndProject(
            @Param("materialId") Long materialId, @Param("projectId") Long projectId);

    @Query("SELECT COALESCE(SUM(cs.stockValue), 0) FROM CurrentStock cs WHERE cs.material.id = :materialId")
    BigDecimal sumStockValueByMaterial(@Param("materialId") Long materialId);

    /**
     * Totals quantity and value for many materials in one grouped read.
     *
     * <p>The batched form of {@link #sumCurrentQuantityByMaterial} and
     * {@link #sumStockValueByMaterial}, which cost two queries per material and were being called
     * once per row from inside a mapper. Both aggregates come back together because a caller that
     * wants one always wants the other.
     *
     * <p>A material with no stock row produces no group, so the caller supplies the zero. Pass a
     * non-empty collection: {@code IN ()} is not valid SQL.
     */
    @Query("""
            SELECT new org.tornotron.echno_backend.inventoryTransaction.MaterialStockTotals(
                       cs.material.id,
                       COALESCE(SUM(cs.currentQuantity), 0.0),
                       COALESCE(SUM(cs.stockValue), 0))
            FROM CurrentStock cs
            WHERE cs.material.id IN :materialIds
            GROUP BY cs.material.id
            """)
    List<MaterialStockTotals> sumStockByMaterialIds(@Param("materialIds") Collection<Long> materialIds);

    /**
     * Counts distinct materials for many storage locations in one grouped read.
     *
     * <p>Replaces a per-location count that ran once per row on four listing paths. A location
     * holding nothing produces no group, so the caller supplies the zero. Pass a non-empty
     * collection: {@code IN ()} is not valid SQL.
     */
    @Query("""
            SELECT new org.tornotron.echno_backend.inventoryTransaction.StorageLocationItemCount(
                       cs.storageLocation.id, COUNT(DISTINCT cs.material.id))
            FROM CurrentStock cs
            WHERE cs.storageLocation.id IN :storageLocationIds AND cs.organization.id = :organizationId
            GROUP BY cs.storageLocation.id
            """)
    List<StorageLocationItemCount> countDistinctMaterialsByStorageLocationIds(
            @Param("storageLocationIds") Collection<Long> storageLocationIds,
            @Param("organizationId") Long organizationId);

    List<CurrentStock> findByMaterialIdAndOrganization_Id(Long materialId, Long organizationId);

    List<CurrentStock> findByProjectId(Long projectId);
}
