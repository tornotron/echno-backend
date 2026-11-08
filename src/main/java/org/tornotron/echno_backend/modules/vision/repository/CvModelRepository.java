package org.tornotron.echno_backend.modules.vision.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.modules.vision.domain.CvModel;

public interface CvModelRepository extends JpaRepository<CvModel, UUID> {

    @Query("SELECT m FROM CvModel m WHERE m.modelKey = :modelKey")
    Optional<CvModel> findByModelKey(@Param("modelKey") String modelKey);

    /** The row lock an activation takes, so two activations of one model run one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM CvModel m WHERE m.modelKey = :modelKey")
    Optional<CvModel> findByModelKeyForUpdate(@Param("modelKey") String modelKey);

    @Query("SELECT m FROM CvModel m ORDER BY m.modelKey")
    List<CvModel> findPage(Pageable pageable);
}
