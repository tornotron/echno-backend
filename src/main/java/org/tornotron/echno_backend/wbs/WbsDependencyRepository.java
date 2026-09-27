package org.tornotron.echno_backend.wbs;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WbsDependencyRepository extends JpaRepository<WbsDependency, Long> {

    // Every read names the organization, so a foreign id reads as absent even where the
    // orgFilter is not enabled.
    @Query("SELECT d FROM WbsDependency d JOIN FETCH d.predecessor JOIN FETCH d.successor "
            + "WHERE d.projectId = :projectId AND d.organization.id = :orgId "
            + "ORDER BY d.successor.wbsCode, d.predecessor.wbsCode")
    List<WbsDependency> findByProject(@Param("projectId") Long projectId, @Param("orgId") Long orgId);

    @Query("SELECT d FROM WbsDependency d WHERE d.id = :id AND d.projectId = :projectId AND d.organization.id = :orgId")
    Optional<WbsDependency> findScoped(@Param("id") Long id, @Param("projectId") Long projectId,
                                       @Param("orgId") Long orgId);

    boolean existsByPredecessor_IdAndSuccessor_Id(Long predecessorId, Long successorId);
}
