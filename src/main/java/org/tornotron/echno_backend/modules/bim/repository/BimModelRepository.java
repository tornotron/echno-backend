package org.tornotron.echno_backend.modules.bim.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.bim.domain.BimModel;

@Repository
public interface BimModelRepository extends JpaRepository<BimModel, UUID> {

    // JPQL rather than findById so the orgFilter applies; a stranger's id reads as absent.
    @Query("SELECT m FROM BimModel m WHERE m.id = :id")
    Optional<BimModel> findByIdScoped(@Param("id") UUID id);

    List<BimModel> findByProjectIdOrderByCreatedAtAsc(Long projectId);

    boolean existsByProjectIdAndNameIgnoreCase(Long projectId, String name);
}
