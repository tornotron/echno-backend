package org.tornotron.echno_backend.modules.vision.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.modules.vision.domain.CvModelVersion;
import org.tornotron.echno_backend.modules.vision.domain.CvModelVersionStatus;

public interface CvModelVersionRepository extends JpaRepository<CvModelVersion, UUID> {

    @Query("SELECT v FROM CvModelVersion v WHERE v.modelId = :modelId AND v.version = :version")
    Optional<CvModelVersion> findByModelAndVersion(@Param("modelId") UUID modelId, @Param("version") String version);

    @Query("SELECT v FROM CvModelVersion v WHERE v.modelId IN :modelIds ORDER BY v.createdAt")
    List<CvModelVersion> findByModels(@Param("modelIds") Collection<UUID> modelIds);

    @Query("SELECT v FROM CvModelVersion v WHERE v.modelId = :modelId AND v.status = :status")
    List<CvModelVersion> findByModelAndStatus(@Param("modelId") UUID modelId,
                                              @Param("status") CvModelVersionStatus status);
}
