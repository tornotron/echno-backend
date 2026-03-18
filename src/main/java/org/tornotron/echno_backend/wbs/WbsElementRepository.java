package org.tornotron.echno_backend.wbs;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface WbsElementRepository extends JpaRepository<WbsElement, Long> {

    List<WbsElement> findByProjectIdAndParentIsNullOrderBySortOrderAsc(Long projectId);

    List<WbsElement> findByProjectIdOrderByWbsCodeAsc(Long projectId);

    List<WbsElement> findByParentIdOrderBySortOrderAsc(Long parentId);

    Optional<WbsElement> findByProjectIdAndWbsCode(Long projectId, String wbsCode);

    boolean existsByProjectIdAndWbsCode(Long projectId, String wbsCode);

    List<WbsElement> findByProjectIdAndIsLeafTrueOrderByWbsCodeAsc(Long projectId);

    @Query("SELECT w FROM WbsElement w WHERE w.project.id = :projectId AND w.level = :level ORDER BY w.sortOrder")
    List<WbsElement> findByProjectIdAndLevel(Long projectId, Integer level);

    Optional<WbsElement> findByIdAndOrganization_Id(Long id, Long organizationId);

    List<WbsElement> findByProjectIdAndOrganization_IdOrderByWbsCodeAsc(Long projectId, Long organizationId);

    List<WbsElement> findByProjectIdAndParentIsNullAndOrganization_IdOrderBySortOrderAsc(Long projectId, Long organizationId);

    List<WbsElement> findByProjectIdAndIsLeafTrueAndOrganization_IdOrderByWbsCodeAsc(Long projectId, Long organizationId);
}
