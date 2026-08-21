package org.tornotron.echno_backend.inspection.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.inspection.domain.Inspection;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InspectionRepository
        extends JpaRepository<Inspection, UUID>, JpaSpecificationExecutor<Inspection> {

    /**
     * Org-scoped lookup by id. Uses JPQL (not {@code find()} by primary key) so the
     * Hibernate {@code orgFilter} is applied, preventing cross-tenant reads. The
     * check items and defects load lazily while the transaction is still open.
     */
    @Query("SELECT i FROM Inspection i WHERE i.id = :id")
    Optional<Inspection> findByIdScoped(@Param("id") UUID id);

    /**
     * Dedupe guard for AI compliance generation: true when a compliance inspection
     * for this project already references the given rule in this organization. The
     * organization is included explicitly so the check is correct even if the
     * Hibernate {@code orgFilter} is not active on the calling thread.
     */
    boolean existsByProjectIdAndComplianceRuleRefAndOrganization_Id(Long projectId,
                                                                    String complianceRuleRef,
                                                                    Long organizationId);
}
