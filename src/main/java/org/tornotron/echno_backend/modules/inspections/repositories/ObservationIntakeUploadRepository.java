package org.tornotron.echno_backend.modules.inspections.repositories;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.modules.inspections.domain.ObservationIntakeUpload;

public interface ObservationIntakeUploadRepository extends JpaRepository<ObservationIntakeUpload, UUID> {

    /** Org-scoped lookup by id: JPQL rather than {@code find()} so the Hibernate orgFilter applies. */
    @Query("SELECT u FROM ObservationIntakeUpload u WHERE u.id = :id")
    Optional<ObservationIntakeUpload> findByIdScoped(@Param("id") UUID id);
}
