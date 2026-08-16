package org.tornotron.echno_backend.asset;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AssetRepository extends JpaRepository<Asset, Long> {

    Optional<Asset> findByIdAndOrganization_Id(Long id, Long organizationId);

    boolean existsByAssetIdAndOrganization_Id(String assetId, Long organizationId);
}
