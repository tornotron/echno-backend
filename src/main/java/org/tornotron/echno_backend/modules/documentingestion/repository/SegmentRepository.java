package org.tornotron.echno_backend.modules.documentingestion.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.documentingestion.domain.Segment;

@Repository
public interface SegmentRepository extends JpaRepository<Segment, UUID> {
}
