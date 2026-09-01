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
     * The value of every holding in the organization, in one aggregate.
     *
     * <p>The organization-wide counterpart of {@link #sumStockValueByMaterial}, which is the same
     * aggregate with the material predicate dropped. It exists because a total over a catalogue is
     * not derivable from a page of it: the console used to add up whatever rows one capped listing
     * returned and label the answer "current inventory value", which was two thirds of the truth at
     * 500 rows of a 743-row catalogue and read like a fact.
     *
     * <p>The organization is a predicate here rather than only the Hibernate {@code orgFilter},
     * which is not the ordinary convention on this repository and is deliberate. The filter is off
     * for a bypassed request, and on a sum that is invisible: a total spanning every tenant is
     * still a number, and nobody reading it can tell. The filter still applies on top, which is
     * what {@code MaterialStockSummaryIT} pins.
     *
     * @param organizationId The tenant to total within.
     * @return The value on hand across every project and storage location, zero when nothing is held.
     */
    @Query("SELECT COALESCE(SUM(cs.stockValue), 0) FROM CurrentStock cs "
            + "WHERE cs.organization.id = :organizationId")
    BigDecimal sumStockValueForOrganization(@Param("organizationId") Long organizationId);

    /** {@link #sumStockValueForOrganization} narrowed to one project's balance rows. */
    @Query("SELECT COALESCE(SUM(cs.stockValue), 0) FROM CurrentStock cs "
            + "WHERE cs.organization.id = :organizationId AND cs.project.id = :projectId")
    BigDecimal sumStockValueForProject(@Param("organizationId") Long organizationId,
                                       @Param("projectId") Long projectId);

    /**
     * How many holdings the value total cannot price.
     *
     * <p>A receipt posted with no unit cost adds quantity and no value, so the balance row it
     * lands on carries stock at a value of zero. That zero is written by the posting path, not
     * chosen by the sum, so the sum has nothing to exclude and no reason to refuse: it counts the
     * row at the value the row holds. What it must not do is stay quiet about it, because a total
     * that silently understates is the failure this whole read exists to correct. This is the
     * count that lets the caller say so.
     *
     * @param organizationId The tenant to count within.
     * @return How many balance rows hold a positive quantity at no value.
     */
    @Query("SELECT COUNT(cs.id) FROM CurrentStock cs WHERE cs.organization.id = :organizationId "
            + "AND cs.currentQuantity > 0 AND cs.stockValue <= 0")
    long countUnvaluedHoldingsForOrganization(@Param("organizationId") Long organizationId);

    /** {@link #countUnvaluedHoldingsForOrganization} narrowed to one project's balance rows. */
    @Query("SELECT COUNT(cs.id) FROM CurrentStock cs WHERE cs.organization.id = :organizationId "
            + "AND cs.project.id = :projectId AND cs.currentQuantity > 0 AND cs.stockValue <= 0")
    long countUnvaluedHoldingsForProject(@Param("organizationId") Long organizationId,
                                         @Param("projectId") Long projectId);

    /**
     * How many distinct materials a project carries a balance row for.
     *
     * <p>The project-scope candidate set, matching the one
     * {@code LowStockRepository.findLowStockForProject} uses: at project scope the materials that
     * count are the ones held there, not the whole catalogue, because every catalogue material
     * would otherwise be reported against a project that has never carried it. A row sitting at
     * zero still counts, because the project carries the material and the value total sums that
     * same row.
     *
     * @param organizationId The tenant to count within.
     * @param projectId The project to count at.
     * @return How many materials have a balance row on the project.
     */
    @Query("SELECT COUNT(DISTINCT cs.material.id) FROM CurrentStock cs "
            + "WHERE cs.organization.id = :organizationId AND cs.project.id = :projectId")
    long countDistinctMaterialsForProject(@Param("organizationId") Long organizationId,
                                          @Param("projectId") Long projectId);

    /** The distinct units of measure across the materials {@link #countDistinctMaterialsForProject} counts. */
    @Query("SELECT COUNT(DISTINCT cs.material.unit) FROM CurrentStock cs "
            + "WHERE cs.organization.id = :organizationId AND cs.project.id = :projectId")
    long countDistinctUnitsForProject(@Param("organizationId") Long organizationId,
                                      @Param("projectId") Long projectId);

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
