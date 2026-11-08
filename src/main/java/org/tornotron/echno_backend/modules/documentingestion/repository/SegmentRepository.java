package org.tornotron.echno_backend.modules.documentingestion.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.documentingestion.domain.Segment;

@Repository
public interface SegmentRepository extends JpaRepository<Segment, UUID> {

    @Query("SELECT s FROM Segment s WHERE s.id = :id")
    Optional<Segment> findByIdScoped(@Param("id") UUID id);
}
