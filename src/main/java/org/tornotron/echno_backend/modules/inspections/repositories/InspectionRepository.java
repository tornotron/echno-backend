package org.tornotron.echno_backend.modules.inspections.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.tornotron.echno_backend.modules.inspections.domain.Inspection;
import org.tornotron.echno_backend.modules.inspections.domain.InspectionDefect;

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

    /** Organization-explicit existence check, for reads that must not lean on the session filter. */
    boolean existsByIdAndOrganization_Id(UUID id, Long organizationId);

    /** The inspection one check item belongs to, for a review that sets that item's result. */
    @Query("SELECT DISTINCT i FROM Inspection i JOIN i.checkItems c WHERE c.id = :checkItemId")
    Optional<Inspection> findByCheckItemIdScoped(@Param("checkItemId") UUID checkItemId);

    /** The inspection one defect belongs to, for a review that attaches to that defect. */
    @Query("SELECT DISTINCT i FROM Inspection i JOIN i.defects d WHERE d.id = :defectId")
    Optional<Inspection> findByDefectIdScoped(@Param("defectId") UUID defectId);

    /** The project an inspection belongs to, without loading its check points and defects. */
    @Query("SELECT i.projectId FROM Inspection i WHERE i.id = :id")
    Optional<Long> findProjectIdByIdScoped(@Param("id") UUID id);

    /** A defect by id, organization-explicit through its inspection; defects have no repository of their own. */
    @Query("SELECT d FROM InspectionDefect d WHERE d.id = :id AND d.inspection.organization.id = :organizationId")
    Optional<InspectionDefect> findDefectByIdAndOrganizationId(@Param("id") UUID id,
                                                              @Param("organizationId") Long organizationId);

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
