package org.tornotron.echno_backend.asset;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
