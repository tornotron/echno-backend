package org.tornotron.echno_backend.modules.documentingestion.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.documentingestion.domain.TenderProfile;

@Repository
public interface TenderProfileRepository extends JpaRepository<TenderProfile, UUID> {

    @Query("SELECT t FROM TenderProfile t WHERE t.packageId = :packageId")
    Optional<TenderProfile> findByPackage(@Param("packageId") UUID packageId);
}
