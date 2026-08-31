package org.tornotron.echno_backend.material.lowstock;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.material.Material;

/**
 * The reorder-level comparison, at each of the three scopes stock is held at.
 *
 * <p>Kept off {@code MaterialRepository} because these are three long grouped reads answering one
 * question, and none of them returns a material: they return a material's position against a
 * threshold, which is a different thing and has its own projection.
 *
 * <h2>What counts as configured</h2>
 *
 * <p>A null reorder level means nobody set one, and a material with none is not a candidate at any
 * scope. A level of zero is treated as set, because the web app treats it as set and the point of
 * this query is to agree with the badge the user is already looking at rather than to disagree with
 * it by a rule of its own. It flags the material only once it has nothing on hand.
 *
 * <h2>At or below, not below</h2>
 *
 * <p>The comparison is {@code <=}. A reorder level is the level at which a reorder is placed, so
 * sitting exactly on it is the moment to act, not the moment before. The web app's six client-side
 * comparisons are all {@code <=} as well, which is the other half of the reason: an endpoint that
 * disagreed with the badge by one boundary case would be worse than no endpoint.
 *
 * <h2>Why organization scope left-joins and the other two do not</h2>
 *
 * <p>At organization scope the candidate set is the catalogue, so a material with no stock row
 * anywhere has to appear: it has nothing on hand, which is as far below its level as it is possible
 * to be, and an inner join would silently drop exactly the materials most worth seeing. At project
 * and storage-location scope the candidate set is what is held there. Every catalogue material would
 * otherwise be reported as out of stock at every project that has never carried it, which is a list
 * of everything and tells nobody anything.
 *
 * <h2>Ordering</h2>
 *
 * <p>Most depleted first, as the fraction of the level that is missing rather than the absolute
 * shortfall, because absolute shortfall is in the material's own unit and 25,000 bricks would
 * outrank a site with no cement at all. A level of zero cannot be divided by, and a material at or
 * below a level of zero has nothing, so it sorts as fully depleted. The material id breaks ties, so
 * paging over the result is stable.
 */
public interface LowStockRepository extends Repository<Material, Long> {

    /**
     * Every material in the catalogue whose stock across the whole organization has reached its
     * reorder level.
     *
     * <p>The count query re-states the aggregate as a correlated subquery rather than counting the
     * groups, because a JPQL count cannot be derived from a query carrying {@code GROUP BY} and
     * {@code HAVING}.
     *
     * @param organizationId The tenant to read within.
     * @param pageable Which page to return. Pass it unsorted: the query orders by severity.
     * @return A page of materials at or below their level, most depleted first.
     */
    @Query(value = """
            SELECT new org.tornotron.echno_backend.material.lowstock.LowStockRow(
                       m.id, m.sku, m.materialName, m.unit, m.moq, m.reorderLevel,
                       COALESCE(SUM(cs.currentQuantity), 0.0))
            FROM Material m
            LEFT JOIN CurrentStock cs ON cs.material = m AND cs.organization.id = :organizationId
            WHERE m.organization.id = :organizationId AND m.reorderLevel IS NOT NULL
            GROUP BY m.id, m.sku, m.materialName, m.unit, m.moq, m.reorderLevel
            HAVING COALESCE(SUM(cs.currentQuantity), 0.0) <= m.reorderLevel
            ORDER BY CASE WHEN m.reorderLevel > 0
                          THEN (m.reorderLevel - COALESCE(SUM(cs.currentQuantity), 0.0)) / m.reorderLevel
                          ELSE 1.0 END DESC,
                     m.id ASC
            """,
            countQuery = """
            SELECT COUNT(m) FROM Material m
            WHERE m.organization.id = :organizationId AND m.reorderLevel IS NOT NULL
              AND COALESCE((SELECT SUM(cs.currentQuantity) FROM CurrentStock cs
                            WHERE cs.material = m AND cs.organization.id = :organizationId), 0.0)
                  <= m.reorderLevel
            """)
    Page<LowStockRow> findLowStockForOrganization(@Param("organizationId") Long organizationId,
                                                  Pageable pageable);

    /**
     * Every material held on one project whose stock across that project's storage locations has
     * reached the material's reorder level.
     *
     * <p>Per-location overrides are not consulted here and cannot be: a project holds stock at
     * several locations, so there is no single override that applies to the total.
     *
     * @param organizationId The tenant to read within.
     * @param projectId The project whose stock to total.
     * @param pageable Which page to return. Pass it unsorted: the query orders by severity.
     * @return A page of materials at or below their level on that project, most depleted first.
     */
    @Query(value = """
            SELECT new org.tornotron.echno_backend.material.lowstock.LowStockRow(
                       m.id, m.sku, m.materialName, m.unit, m.moq, m.reorderLevel,
                       SUM(cs.currentQuantity))
            FROM CurrentStock cs
            JOIN cs.material m
            WHERE cs.organization.id = :organizationId AND cs.project.id = :projectId
              AND m.reorderLevel IS NOT NULL
            GROUP BY m.id, m.sku, m.materialName, m.unit, m.moq, m.reorderLevel
            HAVING SUM(cs.currentQuantity) <= m.reorderLevel
            ORDER BY CASE WHEN m.reorderLevel > 0
                          THEN (m.reorderLevel - SUM(cs.currentQuantity)) / m.reorderLevel
                          ELSE 1.0 END DESC,
                     m.id ASC
            """,
            countQuery = """
            SELECT COUNT(DISTINCT m.id)
            FROM CurrentStock cs
            JOIN cs.material m
            WHERE cs.organization.id = :organizationId AND cs.project.id = :projectId
              AND m.reorderLevel IS NOT NULL
              AND (SELECT SUM(sub.currentQuantity) FROM CurrentStock sub
                   WHERE sub.material = m AND sub.project.id = :projectId
                     AND sub.organization.id = :organizationId)
                  <= m.reorderLevel
            """)
    Page<LowStockRow> findLowStockForProject(@Param("organizationId") Long organizationId,
                                             @Param("projectId") Long projectId,
                                             Pageable pageable);

    /**
     * Every material held at one storage location whose stock there has reached the reorder level
     * in force at that location.
     *
     * <p>This is the only scope where a {@code MaterialLocationThreshold} means anything, and it is
     * consulted for the reorder level and the minimum order quantity both. A location with no
     * override, or one whose override leaves the field null, falls back to the material's global
     * level, which is the fallback the override entity documents.
     *
     * <p>The material is a candidate only if some level survives that fallback, so a material with
     * neither an override nor a global level is left alone here exactly as it is at the other two
     * scopes.
     *
     * <p>One row per material: the unique constraint on {@code CurrentStock} allows only one
     * balance per material, project and location, so nothing needs grouping.
     *
     * @param organizationId The tenant to read within.
     * @param projectId The project the location holds stock for.
     * @param storageLocationId The storage location to read at.
     * @param pageable Which page to return. Pass it unsorted: the query orders by severity.
     * @return A page of materials at or below their effective level there, most depleted first.
     */
    @Query(value = """
            SELECT new org.tornotron.echno_backend.material.lowstock.LowStockRow(
                       m.id, m.sku, m.materialName, m.unit,
                       COALESCE(t.moq, m.moq), COALESCE(t.reorderLevel, m.reorderLevel),
                       cs.currentQuantity)
            FROM CurrentStock cs
            JOIN cs.material m
            LEFT JOIN MaterialLocationThreshold t
                 ON t.material = m AND t.storageLocation.id = :storageLocationId
                    AND t.organization.id = :organizationId
            WHERE cs.organization.id = :organizationId AND cs.project.id = :projectId
              AND cs.storageLocation.id = :storageLocationId
              AND COALESCE(t.reorderLevel, m.reorderLevel) IS NOT NULL
              AND cs.currentQuantity <= COALESCE(t.reorderLevel, m.reorderLevel)
            ORDER BY CASE WHEN COALESCE(t.reorderLevel, m.reorderLevel) > 0
                          THEN (COALESCE(t.reorderLevel, m.reorderLevel) - cs.currentQuantity)
                               / COALESCE(t.reorderLevel, m.reorderLevel)
                          ELSE 1.0 END DESC,
                     m.id ASC
            """,
            countQuery = """
            SELECT COUNT(cs)
            FROM CurrentStock cs
            JOIN cs.material m
            LEFT JOIN MaterialLocationThreshold t
                 ON t.material = m AND t.storageLocation.id = :storageLocationId
                    AND t.organization.id = :organizationId
            WHERE cs.organization.id = :organizationId AND cs.project.id = :projectId
              AND cs.storageLocation.id = :storageLocationId
              AND COALESCE(t.reorderLevel, m.reorderLevel) IS NOT NULL
              AND cs.currentQuantity <= COALESCE(t.reorderLevel, m.reorderLevel)
            """)
    Page<LowStockRow> findLowStockAtStorageLocation(@Param("organizationId") Long organizationId,
                                                    @Param("projectId") Long projectId,
                                                    @Param("storageLocationId") Long storageLocationId,
                                                    Pageable pageable);
}
