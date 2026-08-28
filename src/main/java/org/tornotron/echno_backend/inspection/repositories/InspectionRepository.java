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
     * The document number of one inspection, without the inspection.
     *
     * <p>For the places that only need to name an inspection, such as the header of
     * an NCR report. {@link #findByIdScoped} would load every check point and defect
     * the inspection carries to produce one string.
     */
    @Query("SELECT i.inspectionNumber FROM Inspection i WHERE i.id = :id")
    Optional<String> findNumberByIdScoped(@Param("id") UUID id);

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
