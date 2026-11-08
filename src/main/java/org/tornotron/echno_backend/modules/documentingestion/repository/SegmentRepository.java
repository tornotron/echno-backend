package org.tornotron.echno_backend.modules.documentingestion.repository;

import java.util.Collection;
import java.util.List;
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

    /** A document's page segments, in page order; sheet segments belong to the BOQ flow. Bounded by the page count. */
    @Query("SELECT s FROM Segment s WHERE s.documentId = :documentId AND s.sheetName IS NULL "
            + "ORDER BY s.pageFrom, s.createdAt")
    List<Segment> findPageSegments(@Param("documentId") UUID documentId);

    /** Page segments of many documents, for one page of a package's documents. */
    @Query("SELECT s FROM Segment s WHERE s.documentId IN :documentIds AND s.sheetName IS NULL "
            + "ORDER BY s.documentId, s.pageFrom, s.createdAt")
    List<Segment> findPageSegments(@Param("documentIds") Collection<UUID> documentIds);
}
