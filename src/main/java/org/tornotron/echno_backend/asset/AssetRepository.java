package org.tornotron.echno_backend.asset;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AssetRepository extends JpaRepository<Asset, Long> {

    Optional<Asset> findByIdAndOrganization_Id(Long id, Long organizationId);

    boolean existsByAssetIdAndOrganization_Id(String assetId, Long organizationId);

    /**
     * The listing read, with the four to-one associations the DTO flattens fetched in the same
     * query. Without the graph a page of 500 assets costs 500 further selects now that the
     * project the asset is deployed on is a reference rather than a string.
     */
    @Override
    @EntityGraph(attributePaths = {"vendor", "location", "assignedProject", "organization"})
    Page<Asset> findAll(Pageable pageable);

    /**
     * Loads an asset for a site transfer to move under a pessimistic write lock.
     *
     * <p>Two transfers naming the same asset at once would each read it at the sending site and
     * each send it. Taking the row first queues the second, which then reads where the first left
     * the asset and is judged against that.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Asset a WHERE a.id = :id AND a.organization.id = :orgId")
    Optional<Asset> lockByIdAndOrganizationId(@Param("id") Long id, @Param("orgId") Long orgId);

    /**
     * Assets at one storage location of one project that are not in transit on a site transfer,
     * which is the set a transfer from that location can send. Name order, bounded by the page.
     */
    @EntityGraph(attributePaths = {"location", "assignedProject"})
    @Query(value = "SELECT a FROM Asset a WHERE a.organization.id = :orgId AND a.assignedProject.id = :projectId "
            + "AND a.location.id = :locationId AND NOT EXISTS (SELECT 1 FROM SiteTransferItem i "
            + "WHERE i.asset = a AND i.assetInTransit = true) ORDER BY a.name, a.id",
            countQuery = "SELECT count(a) FROM Asset a WHERE a.organization.id = :orgId "
                    + "AND a.assignedProject.id = :projectId AND a.location.id = :locationId "
                    + "AND NOT EXISTS (SELECT 1 FROM SiteTransferItem i WHERE i.asset = a "
                    + "AND i.assetInTransit = true)")
    Page<Asset> findTransferableAtLocation(@Param("orgId") Long orgId, @Param("projectId") Long projectId,
                                           @Param("locationId") Long locationId, Pageable pageable);

    /**
     * Assets on one project with no storage location that are not in transit on a site transfer,
     * the set a transfer naming no sending location can send.
     */
    @EntityGraph(attributePaths = {"location", "assignedProject"})
    @Query(value = "SELECT a FROM Asset a WHERE a.organization.id = :orgId AND a.assignedProject.id = :projectId "
            + "AND a.location IS NULL AND NOT EXISTS (SELECT 1 FROM SiteTransferItem i "
            + "WHERE i.asset = a AND i.assetInTransit = true) ORDER BY a.name, a.id",
            countQuery = "SELECT count(a) FROM Asset a WHERE a.organization.id = :orgId "
                    + "AND a.assignedProject.id = :projectId AND a.location IS NULL "
                    + "AND NOT EXISTS (SELECT 1 FROM SiteTransferItem i WHERE i.asset = a "
                    + "AND i.assetInTransit = true)")
    Page<Asset> findTransferableWithoutLocation(@Param("orgId") Long orgId, @Param("projectId") Long projectId,
                                                Pageable pageable);
}
