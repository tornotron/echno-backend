package org.tornotron.echno_backend.billing.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tornotron.echno_backend.billing.Feature;

import java.util.List;
import java.util.Optional;

public interface FeatureRepository extends JpaRepository<Feature, Long> {
    Optional<Feature> findByCodeAndIsActiveTrue(String code);

    List<Feature> findByIsActiveTrueOrderByCategory();
}
