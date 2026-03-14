package org.tornotron.echno_backend.material;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MaterialRepository extends JpaRepository<Material, Long> {

    Optional<Material> findBySku(String sku);

    List<Material> findByMaterialNameContainingIgnoreCase(String materialName);

    Optional<Material> findByIdAndOrganization_Id(Long id, Long organizationId);

    boolean existsBySku(String sku);
}
