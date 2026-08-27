package org.tornotron.echno_backend.inspection.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.inspection.domain.Ncr;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface NcrRepository extends JpaRepository<Ncr, UUID>, JpaSpecificationExecutor<Ncr> {

    /**
     * Org-scoped lookup by id. Uses JPQL (not {@code find()} by primary key) so the
     * Hibernate {@code orgFilter} is applied, preventing cross-tenant reads.
     */
    @Query("SELECT n FROM Ncr n WHERE n.id = :id")
    Optional<Ncr> findByIdScoped(@Param("id") UUID id);
}
