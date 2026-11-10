package org.tornotron.echno_backend.modules.documentingestion.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.documentingestion.domain.IngestPackage;

@Repository
public interface IngestPackageRepository extends JpaRepository<IngestPackage, UUID> {

    /** A JPQL read, so the tenant filter applies. {@code findById} would skip it. */
    @Query("SELECT p FROM IngestPackage p WHERE p.id = :id")
    Optional<IngestPackage> findByIdScoped(@Param("id") UUID id);

    @Query("SELECT p FROM IngestPackage p ORDER BY p.createdAt DESC")
    Page<IngestPackage> findPage(Pageable pageable);

    /** The packages tied to one project, newest first; JPQL, so the tenant filter applies. */
    @Query("SELECT p FROM IngestPackage p WHERE p.projectId = :projectId ORDER BY p.createdAt DESC")
    Page<IngestPackage> findPageForProject(@Param("projectId") Long projectId, Pageable pageable);
}
