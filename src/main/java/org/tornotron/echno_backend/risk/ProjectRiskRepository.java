package org.tornotron.echno_backend.risk;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository for {@link ProjectRisk}. Every read names the organization. */
public interface ProjectRiskRepository extends JpaRepository<ProjectRisk, UUID> {

    /**
     * One project's register in number order. Bounded by the project: a register is a few dozen
     * risks, a few hundred on the largest job.
     */
    List<ProjectRisk> findByProjectIdAndOrganization_IdOrderByRiskNumberAsc(Long projectId, Long organizationId);

    Optional<ProjectRisk> findByIdAndProjectIdAndOrganization_Id(UUID id, Long projectId, Long organizationId);

    /** The highest number the project has issued, or zero for an empty register. */
    @Query("select coalesce(max(r.riskNumber), 0) from ProjectRisk r "
            + "where r.projectId = :projectId and r.organization.id = :organizationId")
    int findMaxRiskNumber(@Param("projectId") Long projectId, @Param("organizationId") Long organizationId);

    /** The import references the project already holds, so a repeated import skips them. */
    @Query("select r.importRef from ProjectRisk r where r.projectId = :projectId "
            + "and r.organization.id = :organizationId and r.importRef in :refs")
    List<String> findExistingImportRefs(@Param("projectId") Long projectId,
                                        @Param("organizationId") Long organizationId,
                                        @Param("refs") List<String> refs);
}
